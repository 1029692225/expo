// Copyright 2015-present 650 Industries. All rights reserved.

package expo.modules.av;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.unimodules.core.ModuleRegistry;
import org.unimodules.core.Promise;
import org.unimodules.core.arguments.ReadableArguments;
import org.unimodules.core.interfaces.InternalModule;
import org.unimodules.core.interfaces.LifecycleEventListener;
import org.unimodules.core.interfaces.ModuleRegistryConsumer;
import org.unimodules.core.interfaces.services.EventEmitter;
import org.unimodules.core.interfaces.services.UIManager;
import org.unimodules.interfaces.permissions.Permissions;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import expo.modules.av.audio.AudioEventHandler;
import expo.modules.av.audio.AudioEventHandlers;
import expo.modules.av.audio.AudioFocusHandler;
import expo.modules.av.player.PlayerCreator;
import expo.modules.av.player.PlayerManager;
import expo.modules.av.video.VideoView;
import expo.modules.av.video.VideoViewWrapper;

import static android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED;

public class AVManager implements LifecycleEventListener, MediaRecorder.OnInfoListener, AVManagerInterface, InternalModule, ModuleRegistryConsumer {
  private static final String AUDIO_MODE_SHOULD_DUCK_KEY = "shouldDuckAndroid";
  private static final String AUDIO_MODE_INTERRUPTION_MODE_KEY = "interruptionModeAndroid";
  private static final String AUDIO_MODE_PLAY_THROUGH_EARPIECE = "playThroughEarpieceAndroid";
  private static final String AUDIO_MODE_STAYS_ACTIVE_IN_BACKGROUND = "staysActiveInBackground";

  private static final String RECORDING_OPTIONS_KEY = "android";
  private static final String RECORDING_OPTION_EXTENSION_KEY = "extension";
  private static final String RECORDING_OPTION_OUTPUT_FORMAT_KEY = "outputFormat";
  private static final String RECORDING_OPTION_AUDIO_ENCODER_KEY = "audioEncoder";
  private static final String RECORDING_OPTION_SAMPLE_RATE_KEY = "sampleRate";
  private static final String RECORDING_OPTION_NUMBER_OF_CHANNELS_KEY = "numberOfChannels";
  private static final String RECORDING_OPTION_BIT_RATE_KEY = "bitRate";
  private static final String RECORDING_OPTION_MAX_FILE_SIZE_KEY = "maxFileSize";

  private enum AudioInterruptionMode {
    DO_NOT_MIX,
    DUCK_OTHERS,
  }

  private final Context mContext;

  private boolean mAcquiredAudioFocus = false;

  private boolean mAppIsPaused = false;

  private boolean mShouldDuckAudio = true;
  private boolean mIsDuckingAudio = false;
  private boolean mStaysActiveInBackground = false;

  private int mSoundMapKeyCount = 0;

  private final AudioEventHandlers mAudioEventHandlers = AudioEventHandlers.INSTANCE;

  private MediaRecorder mAudioRecorder = null;
  private String mAudioRecordingFilePath = null;
  private long mAudioRecorderUptimeOfLastStartResume = 0L;
  private long mAudioRecorderDurationAlreadyRecorded = 0L;
  private boolean mAudioRecorderIsRecording = false;
  private boolean mAudioRecorderIsPaused = false;

  private ModuleRegistry mModuleRegistry;

  private final PlayerCreator mPlayerCreator;

  private final AudioFocusHandler mAudioFocusHandler;

  public AVManager(final Context reactContext) {
    mContext = reactContext;
    mPlayerCreator = new PlayerCreator(mContext, this);

    AudioManager audioManager = (AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE);

    if (audioManager == null) {
      throw new IllegalStateException("Unable to process sound, AudioManager not available");
    }


    mAudioFocusHandler = new AudioFocusHandler(audioManager);
    // Implemented because of the suggestion here:
    // https://developer.android.com/guide/topics/media-apps/volume-and-earphones.html
//    mNoisyAudioStreamReceiver = new BroadcastReceiver() {
//      @Override
//      public void onReceive(Context context, Intent intent) {
//        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
//          abandonAudioFocus();
//        }
//      }
//    };
//    mContext.registerReceiver(mNoisyAudioStreamReceiver,
//        new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
  }

  @Override
  public ModuleRegistry getModuleRegistry() {
    return mModuleRegistry;
  }

  @Override
  public void setModuleRegistry(ModuleRegistry moduleRegistry) {
    if (mModuleRegistry != null) {
      mModuleRegistry.getModule(UIManager.class).unregisterLifecycleEventListener(this);
    }
    mModuleRegistry = moduleRegistry;
    if (mModuleRegistry != null) {
      mModuleRegistry.getModule(UIManager.class).registerLifecycleEventListener(this);
    }
  }

  @Override
  public Context getContext() {
    return mContext;
  }

  @Override
  public List<Class> getExportedInterfaces() {
    return Collections.singletonList(AVManagerInterface.class);
  }

  private void sendEvent(String eventName, Bundle params) {
    if (mModuleRegistry != null) {
      EventEmitter eventEmitter = mModuleRegistry.getModule(EventEmitter.class);
      if (eventEmitter != null) {
        eventEmitter.emit(eventName, params);
      }
    }
  }

  // LifecycleEventListener

  @Override
  public void onHostResume() {
    if (mAppIsPaused) {
      mAppIsPaused = false;
      for (final AudioEventHandler handler : mAudioEventHandlers.getAudioEventHandlers()) {
        handler.onResume();
      }
    }
  }

  @Override
  public void onHostPause() {
    if (!mAppIsPaused) {
      mAppIsPaused = true;
      for (final AudioEventHandler handler : mAudioEventHandlers.getAudioEventHandlers()) {
        handler.onPause();
      }
    }
  }

  @Override
  public void onHostDestroy() {
    for (final Integer key : mAudioEventHandlers.getAudios().keySet()) {
      removeSoundForKey(key);
    }
    for (final VideoView videoView : mAudioEventHandlers.getVideoHandlers()) {
      videoView.unloadPlayerAndMediaController();
    }

    removeAudioRecorder();
  }

  // Global audio state control API

  @Override
  public void registerVideoViewForAudioLifecycle(final VideoView videoView) {
    mAudioEventHandlers.addVideo(videoView);
  }

  @Override
  public void unregisterVideoViewForAudioLifecycle(final VideoView videoView) {
    mAudioEventHandlers.removeVideo(videoView);
  }

  @Override
  public float getVolumeForDuckAndFocus(final boolean isMuted, final float volume) {
    return (!mAcquiredAudioFocus || isMuted) ? 0.0f : mIsDuckingAudio ? volume / 2.0f : volume;
  }

  private void updateDuckStatusForAllPlayersPlaying() {
    for (final AudioEventHandler handler : mAudioEventHandlers.getAudioEventHandlers()) {
      handler.updateVolumeMuteAndDuck();
    }
  }

  @Override
  public void setAudioMode(final ReadableArguments map) {
    mShouldDuckAudio = map.getBoolean(AUDIO_MODE_SHOULD_DUCK_KEY);
    if (!mShouldDuckAudio) {
      mIsDuckingAudio = false;
      updateDuckStatusForAllPlayersPlaying();
    }

    mStaysActiveInBackground = map.getBoolean(AUDIO_MODE_STAYS_ACTIVE_IN_BACKGROUND);
  }

  // Unified playback API - Audio

  // Rejects the promise and returns null if the PlayerManager is not found.
  private PlayerManager tryGetSoundForKey(final Integer key, final Promise promise) {
    final PlayerManager data = mAudioEventHandlers.getAudios().get(key).get(); //TODO: Convert to kotlin and optional chaining!
    if (data == null && promise != null) {
      promise.reject("E_AUDIO_NOPLAYER", "Player does not exist.");
    }
    return data;
  }

  private void removeSoundForKey(final Integer key) {
    final PlayerManager data = mAudioEventHandlers.getAudios().get(key).get();
    mAudioEventHandlers.removeAudio(key);
    if (data != null) {
      data.release();
    }
  }

  @Override
  public void loadForSound(final Source source, final ReadableArguments arguments, final Promise promise) {
    final int key = mSoundMapKeyCount++;
    final PlayerManager playerManager = mPlayerCreator.createUnloadedPlayerData(source, new Params().update(arguments), getModuleRegistry());
    playerManager.setErrorListener(error -> removeSoundForKey(key));
    mAudioEventHandlers.addAudio(key, playerManager);
    playerManager.load(arguments, new PlayerManager.LoadCompletionListener() {
      @Override
      public void onLoadSuccess(@NonNull Status status) {
        promise.resolve(Arrays.asList(key, status.toBundle()));
      }

      @Override
      public void onLoadError(@NonNull final String error) {
        mAudioEventHandlers.removeAudio(key);
        promise.reject("E_LOAD_ERROR", error, null);
      }
    });

    playerManager.setStatusUpdateListener(status -> {
      Bundle payload = new Bundle();
      payload.putInt("key", key);
      payload.putBundle("status", status.toBundle());
      sendEvent("didUpdatePlaybackStatus", payload);
    });
  }

  @Override
  public void unloadForSound(final Integer key, final Promise promise) {
    if (tryGetSoundForKey(key, promise) != null) {
      removeSoundForKey(key);
      promise.resolve(new Status().toBundle());
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

  @Override
  public void setParamsForSound(final Integer key, final ReadableArguments status, final Promise promise) {
    final PlayerManager data = tryGetSoundForKey(key, promise);
    if (data != null) {
      data.setParams(status, promise);
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

  @Override
  public void replaySound(final Integer key, final ReadableArguments status, final Promise promise) {
    final PlayerManager data = tryGetSoundForKey(key, promise);
    if (data != null) {
      data.setParams(status, promise);
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

  @Override
  public void getStatusForSound(final Integer key, final Promise promise) {
    final PlayerManager data = tryGetSoundForKey(key, promise);
    if (data != null) {
      promise.resolve(data.getStatus().toBundle());
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

// Unified playback API - Video

  private interface VideoViewCallback {
    void runWithVideoView(final VideoView videoView);

  }

  // Rejects the promise if the VideoView is not found, otherwise executes the callback.
  private void tryRunWithVideoView(final Integer tag, final VideoViewCallback callback, final Promise promise) {
    if (mModuleRegistry != null) {
      UIManager uiManager = mModuleRegistry.getModule(UIManager.class);
      if (uiManager != null) {
        uiManager.addUIBlock(tag, new UIManager.UIBlock<VideoViewWrapper>() {
          @Override
          public void resolve(VideoViewWrapper videoViewWrapper) {
            callback.runWithVideoView(videoViewWrapper.getVideoViewInstance());
          }

          @Override
          public void reject(Throwable throwable) {
            promise.reject("E_VIDEO_TAGINCORRECT", "Invalid view returned from registry.");
          }
        }, VideoViewWrapper.class);
      }
    }
  }

  @Override
  public void loadForVideo(final Integer tag, final Source source, final ReadableArguments status, final Promise promise) {
    tryRunWithVideoView(tag, videoView -> videoView.setSource(source, status, promise), promise); // Otherwise, tryRunWithVideoView has already rejected the promise.
  }

  @Override
  public void unloadForVideo(final Integer tag, final Promise promise) {
    tryRunWithVideoView(tag, videoView -> videoView.setSource(null, null, promise), promise); // Otherwise, tryRunWithVideoView has already rejected the promise.
  }

  @Override
  public void setStatusForVideo(final Integer tag, final ReadableArguments params, final Promise promise) {
    tryRunWithVideoView(tag, videoView -> videoView.setParams(params, promise), promise); // Otherwise, tryRunWithVideoView has already rejected the promise.
  }

  @Override
  public void replayVideo(final Integer tag, final ReadableArguments params, final Promise promise) {
    // TODO: consider whether should be status (unlikely) or params
    tryRunWithVideoView(tag, videoView -> videoView.setParams(params, promise), promise); // Otherwise, tryRunWithVideoView has already rejected the promise.
  }

  @Override
  public void getStatusForVideo(final Integer tag, final Promise promise) {
    tryRunWithVideoView(tag, videoView -> promise.resolve(videoView.getStatus()), promise); // Otherwise, tryRunWithVideoView has already rejected the promise.
  }

  // Note that setStatusUpdateCallback happens in the JS for video via onParamsUpdate

  // Recording API

  private boolean isMissingAudioRecordingPermissions() {
    return mModuleRegistry.getModule(Permissions.class).getPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
  }

  // Rejects the promise and returns false if the MediaRecorder is not found.
  private boolean checkAudioRecorderExistsOrReject(final Promise promise) {
    if (mAudioRecorder == null && promise != null) {
      promise.reject("E_AUDIO_NORECORDER", "Recorder does not exist.");
    }
    return mAudioRecorder != null;
  }

  private long getAudioRecorderDurationMillis() {
    if (mAudioRecorder == null) {
      return 0L;
    }
    long duration = mAudioRecorderDurationAlreadyRecorded;
    if (mAudioRecorderIsRecording && mAudioRecorderUptimeOfLastStartResume > 0) {
      duration += SystemClock.uptimeMillis() - mAudioRecorderUptimeOfLastStartResume;
    }
    return duration;
  }

  private Bundle getAudioRecorderStatus() {
    final Bundle map = new Bundle();
    if (mAudioRecorder != null) {
      map.putBoolean("canRecord", true);
      map.putBoolean("isRecording", mAudioRecorderIsRecording);
      map.putInt("durationMillis", (int) getAudioRecorderDurationMillis());
    }
    return map;
  }

  private void removeAudioRecorder() {
    if (mAudioRecorder != null) {
      try {
        mAudioRecorder.stop();
      } catch (final RuntimeException e) {
        // Do nothing-- this just means that the recorder is already stopped,
        // or was stopped immediately after starting.
      }
      mAudioRecorder.release();
      mAudioRecorder = null;
    }

    mAudioRecordingFilePath = null;
    mAudioRecorderIsRecording = false;
    mAudioRecorderIsPaused = false;
    mAudioRecorderDurationAlreadyRecorded = 0L;
    mAudioRecorderUptimeOfLastStartResume = 0L;
  }

  @Override
  public void onInfo(final MediaRecorder mr, final int what, final int extra) {
    switch (what) {
      case MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
        removeAudioRecorder();
        if (mModuleRegistry != null) {
          EventEmitter eventEmitter = mModuleRegistry.getModule(EventEmitter.class);
          if (eventEmitter != null) {
            eventEmitter.emit("Expo.Recording.recorderUnloaded", new Bundle());
          }
        }
      default:
        // Do nothing
    }
  }

  @Override
  public void prepareAudioRecorder(final ReadableArguments options, final Promise promise) {
    if (isMissingAudioRecordingPermissions()) {
      promise.reject("E_MISSING_PERMISSION", "Missing audio recording permissions.");
      return;
    }

    removeAudioRecorder();

    final ReadableArguments androidOptions = options.getArguments(RECORDING_OPTIONS_KEY);

    final String filename = "recording-" + UUID.randomUUID().toString()
        + androidOptions.getString(RECORDING_OPTION_EXTENSION_KEY);
    try {
      final File directory = new File(mContext.getCacheDir() + File.separator + "Audio");
      ensureDirExists(directory);
      mAudioRecordingFilePath = directory + File.separator + filename;
    } catch (final IOException e) {
      // This only occurs in the case that the scoped path is not in this experience's scope,
      // which is never true.
    }

    mAudioRecorder = new MediaRecorder();
    mAudioRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);

    mAudioRecorder.setOutputFormat(androidOptions.getInt(RECORDING_OPTION_OUTPUT_FORMAT_KEY));
    mAudioRecorder.setAudioEncoder(androidOptions.getInt(RECORDING_OPTION_AUDIO_ENCODER_KEY));
    if (androidOptions.containsKey(RECORDING_OPTION_SAMPLE_RATE_KEY)) {
      mAudioRecorder.setAudioSamplingRate(androidOptions.getInt(RECORDING_OPTION_SAMPLE_RATE_KEY));
    }
    if (androidOptions.containsKey(RECORDING_OPTION_NUMBER_OF_CHANNELS_KEY)) {
      mAudioRecorder.setAudioChannels(androidOptions.getInt(RECORDING_OPTION_NUMBER_OF_CHANNELS_KEY));
    }
    if (androidOptions.containsKey(RECORDING_OPTION_BIT_RATE_KEY)) {
      mAudioRecorder.setAudioEncodingBitRate(androidOptions.getInt(RECORDING_OPTION_BIT_RATE_KEY));
    }

    mAudioRecorder.setOutputFile(mAudioRecordingFilePath);

    if (androidOptions.containsKey(RECORDING_OPTION_MAX_FILE_SIZE_KEY)) {
      mAudioRecorder.setMaxFileSize(androidOptions.getInt(RECORDING_OPTION_MAX_FILE_SIZE_KEY));
      mAudioRecorder.setOnInfoListener(this);
    }

    try {
      mAudioRecorder.prepare();
    } catch (final Exception e) {
      promise.reject("E_AUDIO_RECORDERNOTCREATED", "Prepare encountered an error: recorder not prepared", e);
      removeAudioRecorder();
      return;
    }

    final Bundle map = new Bundle();
    map.putString("uri", Uri.fromFile(new File(mAudioRecordingFilePath)).toString());
    map.putBundle("status", getAudioRecorderStatus());
    promise.resolve(map);
  }

  @Override
  public void startAudioRecording(final Promise promise) {
    if (isMissingAudioRecordingPermissions()) {
      promise.reject("E_MISSING_PERMISSION", "Missing audio recording permissions.");
      return;
    }

    if (checkAudioRecorderExistsOrReject(promise)) {
      try {
        if (mAudioRecorderIsPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          mAudioRecorder.resume();
        } else {
          mAudioRecorder.start();
        }
      } catch (final IllegalStateException e) {
        promise.reject("E_AUDIO_RECORDING", "Start encountered an error: recording not started", e);
        return;
      }

      mAudioRecorderUptimeOfLastStartResume = SystemClock.uptimeMillis();
      mAudioRecorderIsRecording = true;
      mAudioRecorderIsPaused = false;

      promise.resolve(getAudioRecorderStatus());
    }
  }

  @Override
  public void pauseAudioRecording(final Promise promise) {
    if (checkAudioRecorderExistsOrReject(promise)) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        promise.reject("E_AUDIO_VERSIONINCOMPATIBLE", "Pausing an audio recording is unsupported on" +
            " Android devices running SDK < 24.");
      } else {
        try {
          mAudioRecorder.pause();
        } catch (final IllegalStateException e) {
          promise.reject("E_AUDIO_RECORDINGPAUSE", "Pause encountered an error: recording not paused", e);
          return;
        }

        mAudioRecorderDurationAlreadyRecorded = getAudioRecorderDurationMillis();
        mAudioRecorderIsRecording = false;
        mAudioRecorderIsPaused = true;

        promise.resolve(getAudioRecorderStatus());
      }
    }
  }

  @Override
  public void stopAudioRecording(final Promise promise) {
    if (checkAudioRecorderExistsOrReject(promise)) {
      try {
        mAudioRecorder.stop();
      } catch (final RuntimeException e) {
        promise.reject("E_AUDIO_RECORDINGSTOP", "Stop encountered an error: recording not stopped", e);
        return;
      }

      mAudioRecorderDurationAlreadyRecorded = getAudioRecorderDurationMillis();
      mAudioRecorderIsRecording = false;
      mAudioRecorderIsPaused = false;

      promise.resolve(getAudioRecorderStatus());
    }
  }

  @Override
  public void getAudioRecordingStatus(final Promise promise) {
    if (checkAudioRecorderExistsOrReject(promise)) {
      promise.resolve(getAudioRecorderStatus());
    }
  }

  @Override
  public void unloadAudioRecorder(final Promise promise) {
    if (checkAudioRecorderExistsOrReject(promise)) {
      removeAudioRecorder();
      promise.resolve(null);
    }
  }

  private static File ensureDirExists(File dir) throws IOException {
    if (!(dir.isDirectory() || dir.mkdirs())) {
      throw new IOException("Couldn't create directory '" + dir + "'");
    }
    return dir;
  }
}
