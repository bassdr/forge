/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2012  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package forge.sound;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.google.common.io.Files;
import com.sipgate.mp3wav.Converter;

/**
 * SoundSystem - a simple sound playback system for Forge.
 * Do not use directly. Instead, use the {@link forge.sound.SoundEffectType} enumeration.
 *
 * @author Agetian
 */
public class AudioClip implements IAudioClip {
    private final int maxSize = 16;

    /**
     * An audio line stays open from Clip.open() to Clip.close(), and nothing closed one during a
     * match: dispose() is reachable only from invalidateSoundCache(), which the audio menu calls.
     * So a session held maxSize lines for every effect it had ever played -- up to 16 x 39 files --
     * and on a device that meters them (PipeWire) the count only ever climbed.
     */
    private static final int MAX_TOTAL_CLIPS = 64;
    /** Close a pooled line once it has gone this long without being played. */
    private static final long IDLE_TIMEOUT_MS = 10_000L;
    private static final long REAP_PERIOD_MS = 5_000L;
    /**
     * Clip.open() has no timeout, and on an exhausted or wedged device it parks in the driver
     * *while holding the mixer's own monitor* -- which also stops clips that are still playing from
     * ever reaching stop() and reporting themselves idle, so the pool asks for another line. Doing
     * it on the caller's thread is what turns a busy sound device into a frozen match.
     */
    private static final long OPEN_TIMEOUT_MS = 3_000L;
    /** After a timed-out open, stop asking the device for a while rather than queueing more. */
    private static final long STALL_BACKOFF_MS = 30_000L;

    private static final AtomicInteger openClips = new AtomicInteger();
    private static final AtomicLong stalledUntil = new AtomicLong();
    private static final Set<AudioClip> pools = ConcurrentHashMap.newKeySet();

    /** Blocking device work only. Separate from the reaper so a wedged open cannot stall it. */
    private static final ExecutorService deviceExec =
            Executors.newCachedThreadPool(runnable -> daemon(runnable, "Forge audio device"));
    private static final ScheduledExecutorService reaper =
            Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "Forge audio reaper"));
    static {
        reaper.scheduleWithFixedDelay(AudioClip::reapIdleClips,
                REAP_PERIOD_MS, REAP_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * Java hands out one device line per Clip, and the ALSA/PipeWire plugin shows each as its own
     * application stream -- so a session that has played 39 distinct effects appears 39 times in
     * the mixer, which no other application does. The JDK ships a software mixer ("Gervill") that
     * mixes any number of lines itself and opens the device ONCE, but it is no longer registered as
     * a MixerProvider, so AudioSystem.getLine cannot return it. Measured on this set: 39 clips
     * playing at once cost 2 PipeWire nodes through Gervill against 137 through the direct device.
     *
     * Needs --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED; null when that is absent,
     * and then everything below behaves exactly as before.
     */
    private static final Mixer softMixer = openSoftMixer();

    private static Mixer openSoftMixer() {
        try {
            Mixer mixer = (Mixer) Class.forName("com.sun.media.sound.SoftMixingMixer")
                    .getDeclaredConstructor().newInstance();
            mixer.open();
            return mixer;
        } catch (Throwable t) {
            // No --add-exports, or a JDK that no longer ships it. One line per sound, as before.
            return null;
        }
    }

    private final String filename;
    private final List<ClipWrapper> clips;
    private boolean failed;
    private static final Map<String, byte[]> audioClips = new HashMap<>(30);

    public static byte[] getAudioClips(File file) throws IOException {
        if (!audioClips.containsKey(file.toString()) ) {
            audioClips.put(file.toString(), Converter.convertFrom(Files.asByteSource(file).openStream()).toByteArray());
        }
        return audioClips.get(file.toString());
    }

    public static boolean fileExists(String fileName) {
        File fSound = SoundSystem.instance.getSoundResource(fileName);
        return fSound != null && fSound.exists();
    }

    public AudioClip(final String filename) {
        this.filename = filename;
        // Played from the game thread, reaped from the reaper thread.
        clips = new CopyOnWriteArrayList<>();
        pools.add(this);
        addClip();
    }

    @Override
    public final void play(float value) {
        if (clips.stream().anyMatch(ClipWrapper::isRunning)) {
            // introduce small delay to make a batch sounds more granular,
            // e.g. when you auto-tap 4 lands the 4 tap sounds should
            // not become completely merged
            waitSoundSystemDelay();
        }
        getIdleClip().start(value);
    }

    @Override
    public final void loop() {
        getIdleClip().loop();
    }

    @Override
    public void dispose() {
        for (ClipWrapper clip : clips) {
            clip.close();
        }
        clips.clear();
        pools.remove(this);
        audioClips.clear();
    }

    @Override
    public final void stop() {
        for (ClipWrapper clip: clips) {
            clip.stop();
        }
    }

    @Override
    public final boolean isDone() {
        return clips.stream().noneMatch(ClipWrapper::isRunning);
    }

    private ClipWrapper getIdleClip() {
        return clips.stream()
                .filter(clip -> !clip.isRunning())
                .findFirst()
                .orElseGet(this::addClip);
    }

    private ClipWrapper addClip() {
        if (clips.size() >= maxSize || failed
                || openClips.get() >= MAX_TOTAL_CLIPS
                || System.currentTimeMillis() < stalledUntil.get()) {
            return ClipWrapper.Dummy;
        }
        Future<ClipWrapper> pending = deviceExec.submit(() -> new ClipWrapper(filename));
        ClipWrapper clip;
        try {
            clip = pending.get(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // The device is not answering. Give up on sound for a while; the alternative is
            // parking the game thread in the driver until someone kills Forge.
            stalledUntil.set(System.currentTimeMillis() + STALL_BACKOFF_MS);
            pending.cancel(true);
            System.err.println("Sound device did not open a line within "
                    + OPEN_TIMEOUT_MS + "ms; muting effects for " + STALL_BACKOFF_MS + "ms");
            return ClipWrapper.Dummy;
        } catch (Exception ex) {
            // A missing or unplayable file, or a device that refused the line. Either way this is
            // a question about sound, and it used to travel up through play() into resolveStack().
            failed = true;
            return ClipWrapper.Dummy;
        }
        if (clip.isFailed()) {
            failed = true;
        } else {
            clips.add(clip);
            openClips.incrementAndGet();
        }
        return clip;
    }

    /**
     * Give the device back the lines nobody is using. Without this the cap above is a one-way
     * ratchet: the count stops climbing, but every line stays open until Forge exits and new
     * effects are answered with silence for the rest of the session.
     */
    private static void reapIdleClips() {
        final long cutoff = System.currentTimeMillis() - IDLE_TIMEOUT_MS;
        for (AudioClip pool : pools) {
            for (ClipWrapper clip : pool.clips) {
                if (clip.isIdleSince(cutoff) && pool.clips.remove(clip)) {
                    // close() can block in the driver exactly as open() can, so keep it off this
                    // thread too -- a wedged device must not stop the rest of the pool being reaped.
                    deviceExec.submit(clip::close);
                }
            }
        }
    }

    private static boolean waitSoundSystemDelay() {
        try {
            Thread.sleep(SoundSystem.DELAY);
            return true;
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    static class ClipWrapper {
        private final Clip clip;
        private boolean started;
        private boolean closed;
        private volatile long lastPlayed = System.currentTimeMillis();
        static final ClipWrapper Dummy = new ClipWrapper();

        private ClipWrapper() {
            clip = null;
        }

        ClipWrapper(String filename) {
            clip = createClip(filename);
            if (clip != null) {
                clip.addLineListener(this::clipStateChanged);
            }
        }

        boolean isFailed() {
            return null == clip;
        }

        void start(float volume) {
            if (null == clip) {
                return;
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                lastPlayed = System.currentTimeMillis();
                applyVolume(volume);
                clip.setMicrosecondPosition(0);
                this.started = false;
                clip.start();
                // with JRE 1.8.0_211 if another thread called clip.setMicrosecondPosition
                // just now, it would deadlock. To prevent this we synchronize this method
                // and wait
                wait(() -> this.started);
            }
        }

        void loop() {
            if (null == clip) {
                return;
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                lastPlayed = System.currentTimeMillis();
                clip.setMicrosecondPosition(0);
                this.started = false;
                clip.loop(Clip.LOOP_CONTINUOUSLY);
                wait(() -> this.started);
            }
        }

        void stop() {
            if (null == clip) {
                return;
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                clip.stop();
            }
        }

        void close() {
            if (null == clip) {
                return;
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                clip.stop();
                clip.close();
            }
            openClips.decrementAndGet();
        }

        boolean isRunning() {
            return clip != null && !closed && (clip.isRunning() || clip.isActive());
        }

        /** Idle, and last played before the cutoff -- so a reap never interrupts audible sound. */
        boolean isIdleSince(long cutoff) {
            return clip != null && !closed && !isRunning() && lastPlayed < cutoff;
        }

        private Clip createClip(String filename) {
            File fSound = SoundSystem.instance.getSoundResource(filename);
            if (fSound == null || !fSound.exists()) {
                throw new IllegalArgumentException("Sound file " + fSound + " does not exist, cannot make a clip of it");
            }
            try {
                ByteArrayInputStream bis = new ByteArrayInputStream(getAudioClips(fSound));
                AudioInputStream stream = AudioSystem.getAudioInputStream(bis);
                AudioFormat format = stream.getFormat();
                DataLine.Info info = new DataLine.Info(Clip.class, stream.getFormat(), ((int) stream.getFrameLength() * format.getFrameSize()));
                Clip clip = (Clip) (softMixer != null ? softMixer.getLine(info) : AudioSystem.getLine(info));
                clip.open(stream);
                return clip;
            } catch (IOException ex) {
                System.err.println("Unable to load sound file: " + filename);
            } catch (LineUnavailableException ex) {
                System.err.println("Error initializing sound system: " + ex);
            } catch (UnsupportedAudioFileException ex) {
                System.err.println("Unsupported file type of the sound file: " + fSound + " - " + ex.getMessage());
                return null;
            }
            throw new MissingResourceException("Sound clip failed to load", this.getClass().getName(), filename);
        }

        private void applyVolume(float volume) {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (20.0 * Math.log10(Math.max(volume, 0.0001)));
                dB = Math.max(dB, gain.getMinimum());
                dB = Math.min(dB, gain.getMaximum());
                gain.setValue(dB);
            }
        }

        private void clipStateChanged(LineEvent lineEvent) {
            started |= lineEvent.getType() == LineEvent.Type.START;
        }

        private void wait(Supplier<Boolean> completed) {
            final int attempts = 5;
            for (int i = 0; i < attempts; i++) {
                if (completed.get() || !waitSoundSystemDelay()) {
                    break;
                }
            }
        }
    }
}
