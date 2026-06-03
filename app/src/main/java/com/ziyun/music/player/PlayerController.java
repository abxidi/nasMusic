package com.ziyun.music.player;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.ziyun.music.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlayerController {
    public interface Listener {
        void onPlaybackChanged();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (playing && currentSong != null) {
                if (isStreamingCurrent()) {
                    positionSec = currentStreamPositionSec();
                    notifyChanged();
                } else {
                    positionSec++;
                    if (positionSec >= currentSong.durationSec) {
                        next();
                    } else {
                        notifyChanged();
                    }
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final List<Song> queue = new ArrayList<>();
    private Listener listener;
    private Song currentSong;
    private int currentIndex;
    private int positionSec;
    private boolean playing = true;
    private boolean shuffle;
    private boolean repeat;
    private MediaPlayer mediaPlayer;
    private boolean streamPrepared;

    public PlayerController(List<Song> initialQueue) {
        playQueue(initialQueue, 0);
        handler.post(ticker);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void release() {
        handler.removeCallbacks(ticker);
        releaseStream();
        listener = null;
    }

    public Song currentSong() {
        return currentSong;
    }

    public List<Song> queue() {
        return Collections.unmodifiableList(queue);
    }

    public int currentIndex() {
        return currentIndex;
    }

    public int positionSec() {
        return positionSec;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public void playSong(Song song, List<Song> source) {
        int index = 0;
        List<Song> nextQueue = source == null || source.isEmpty() ? Collections.singletonList(song) : source;
        for (int i = 0; i < nextQueue.size(); i++) {
            if (nextQueue.get(i).id.equals(song.id)) {
                index = i;
                break;
            }
        }
        playQueue(nextQueue, index);
    }

    public void playQueue(List<Song> songs, int startIndex) {
        releaseStream();
        queue.clear();
        if (songs != null) {
            queue.addAll(songs);
        }
        if (queue.isEmpty()) {
            currentSong = null;
            currentIndex = 0;
            positionSec = 0;
            playing = false;
        } else {
            currentIndex = Math.max(0, Math.min(startIndex, queue.size() - 1));
            currentSong = queue.get(currentIndex);
            positionSec = 0;
            playing = true;
            prepareStreamIfNeeded();
        }
        notifyChanged();
    }

    public void toggle() {
        if (isStreamingCurrent()) {
            if (playing) {
                pauseStream();
                playing = false;
            } else {
                playing = true;
                startStreamIfReady();
            }
            notifyChanged();
            return;
        }
        playing = !playing;
        notifyChanged();
    }

    public void next() {
        if (queue.isEmpty()) {
            return;
        }
        if (shuffle) {
            currentIndex = (currentIndex + 3) % queue.size();
        } else if (currentIndex < queue.size() - 1) {
            currentIndex++;
        } else if (repeat) {
            currentIndex = 0;
        } else {
            releaseStream();
            playing = false;
            positionSec = currentSong == null ? 0 : currentSong.durationSec;
            notifyChanged();
            return;
        }
        currentSong = queue.get(currentIndex);
        positionSec = 0;
        playing = true;
        prepareStreamIfNeeded();
        notifyChanged();
    }

    public void previous() {
        if (queue.isEmpty()) {
            return;
        }
        if (positionSec > 4) {
            positionSec = 0;
            if (isStreamingCurrent() && mediaPlayer != null && streamPrepared) {
                mediaPlayer.seekTo(0);
                startStreamIfReady();
            }
        } else if (currentIndex > 0) {
            currentIndex--;
            currentSong = queue.get(currentIndex);
            positionSec = 0;
            prepareStreamIfNeeded();
        }
        playing = true;
        notifyChanged();
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        notifyChanged();
    }

    public void toggleRepeat() {
        repeat = !repeat;
        notifyChanged();
    }

    public void moveQueueItem(int from, int to) {
        if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size() || from == to) {
            return;
        }
        Song moving = queue.remove(from);
        queue.add(to, moving);
        currentIndex = queue.indexOf(currentSong);
        notifyChanged();
    }

    private void prepareStreamIfNeeded() {
        releaseStream();
        if (!isStreamingCurrent()) {
            return;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(currentSong.streamUrl);
            mediaPlayer.setOnPreparedListener(player -> {
                streamPrepared = true;
                if (playing) {
                    player.start();
                }
                notifyChanged();
            });
            mediaPlayer.setOnCompletionListener(player -> next());
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                playing = false;
                streamPrepared = false;
                notifyChanged();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            releaseStream();
            playing = false;
        }
    }

    private boolean isStreamingCurrent() {
        return currentSong != null && currentSong.hasStreamUrl();
    }

    private void startStreamIfReady() {
        if (mediaPlayer == null) {
            prepareStreamIfNeeded();
            return;
        }
        if (streamPrepared && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
    }

    private void pauseStream() {
        if (mediaPlayer != null && streamPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            positionSec = currentStreamPositionSec();
        }
    }

    private int currentStreamPositionSec() {
        if (mediaPlayer == null || !streamPrepared) {
            return positionSec;
        }
        try {
            return Math.max(0, mediaPlayer.getCurrentPosition() / 1000);
        } catch (IllegalStateException e) {
            return positionSec;
        }
    }

    private void releaseStream() {
        streamPrepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (IllegalStateException ignored) {
                mediaPlayer.release();
            }
            mediaPlayer = null;
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onPlaybackChanged();
        }
    }
}
