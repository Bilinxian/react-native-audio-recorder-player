package com.dooboolab.audiorecorderplayer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.PermissionListener;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

public class RNAudioRecorderPlayerModule extends ReactContextBaseJavaModule implements PermissionListener {

    private final String tag = "RNAudioRecorderPlayer";
    private final String defaultFileName = "sound.mp4";
    private String audioFileURL = "";
    private int subsDurationMillis = 500;
    private boolean _meteringEnabled = false;
    private MediaRecorder mediaRecorder = null;
    private MediaPlayer mediaPlayer = null;
    private Runnable recorderRunnable = null;
    private TimerTask mTask = null;
    private Timer mTimer = null;
    private long pausedRecordTime = 0L;
    private long totalPausedRecordTime = 0L;
    private AudioManager audioManager = null;
    private final Handler recordHandler = new Handler(Looper.getMainLooper());
    private final ReactApplicationContext reactContext;
    private final String[] permissions = new String[]{Manifest.permission.RECORD_AUDIO};

    private int audioSourceAndroid = MediaRecorder.AudioSource.MIC;
    private int output_format = MediaRecorder.OutputFormat.MPEG_4;
    private int audio_encoder = MediaRecorder.AudioEncoder.AAC;
    private int samplingRate = 48000;
    private int bitRate = 128000;
    private int numChannels = 2;
    private BroadcastReceiver receiver;
    private boolean useBluetooth = false;
    private int defaultAudioMode;

    public RNAudioRecorderPlayerModule(ReactApplicationContext context) {
        reactContext = context;
    }

    @NonNull
    @Override
    public String getName() {
        return tag;
    }


    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        return requestCode == 200 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void initialize() {
        super.initialize();

        audioManager = (AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE);
        defaultAudioMode = audioManager.getMode();
    }

    @Override
    public void onCatalystInstanceDestroy() {
        // 通知线程可以结束了
        audioManager.setMode(defaultAudioMode);
    }

    @ReactMethod
    public void startRecorder(String path, ReadableMap audioSet, Boolean meteringEnabled, Promise promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ActivityCompat.checkSelfPermission(reactContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions((getCurrentActivity()), permissions, 0);
                    promise.reject("No permission granted.", "Try again after adding permission.");

                }
            }
        } catch (NullPointerException ne) {
            Log.e(tag, ne.toString());
            promise.reject("No permission granted.", "Try again after adding permission.");
            return;
        }
        audioFileURL = "DEFAULT".equals(path) ? reactContext.getCacheDir() + "/" + defaultFileName : path;
        _meteringEnabled = meteringEnabled;
        if (mediaRecorder == null) {
            mediaRecorder = new MediaRecorder();
        }

        if (audioSet != null) {

            if (audioSet.hasKey("AudioSourceAndroid"))
                audioSourceAndroid = audioSet.getInt("AudioSourceAndroid");

            if (audioSet.hasKey("OutputFormatAndroid"))
                output_format = audioSet.getInt("OutputFormatAndroid");

            if (audioSet.hasKey("AudioEncoderAndroid"))
                audio_encoder = audioSet.getInt("AudioEncoderAndroid");

            if (audioSet.hasKey("AudioSamplingRateAndroid"))
                samplingRate = audioSet.getInt("AudioSamplingRateAndroid");

            if (audioSet.hasKey("AudioEncodingBitRateAndroid"))
                bitRate = audioSet.getInt("AudioEncodingBitRateAndroid");

            if (audioSet.hasKey("AudioChannelsAndroid"))
                numChannels = audioSet.getInt("AudioChannelsAndroid");
            if (audioSet.hasKey("isBluetoothSco"))
                useBluetooth = audioSet.getBoolean("isBluetoothSco");
            mediaRecorder.setAudioSource(audioSourceAndroid);
            mediaRecorder.setOutputFormat(output_format);
            mediaRecorder.setAudioEncoder(audio_encoder);
            mediaRecorder.setAudioSamplingRate(samplingRate);
            mediaRecorder.setAudioEncodingBitRate(bitRate);
            mediaRecorder.setAudioChannels(numChannels);

        } else {
            mediaRecorder.setAudioSource(audioSourceAndroid);
            mediaRecorder.setOutputFormat(output_format);
            mediaRecorder.setAudioEncoder(audio_encoder);
            mediaRecorder.setAudioSamplingRate(samplingRate);
            mediaRecorder.setAudioEncodingBitRate(bitRate);
        }
        mediaRecorder.setOutputFile(audioFileURL);

        try {
            mediaRecorder.prepare();
            totalPausedRecordTime = 0L;
            if (audioSet != null && useBluetooth && audioManager.isBluetoothScoAvailableOffCall()) {
                audioManager.requestAudioFocus(afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                registerReceiver(promise);
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.startBluetoothSco();
                return;
            }
            startRecorder(promise);

        } catch (Exception e) {
            e.printStackTrace();
            promise.reject("startRecord", e.getMessage());
        }
    }

    private final AudioManager.OnAudioFocusChangeListener afChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
                case AudioManager.AUDIOFOCUS_REQUEST_FAILED:

                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    if (audioManager != null)
                        audioManager.abandonAudioFocus(afChangeListener);
                    break;

            }
        }
    };

    private void registerReceiver(Promise promise) {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                Log.e(tag, "state" + state);
                if (AudioManager.SCO_AUDIO_STATE_CONNECTED == state) {

                    audioManager.setBluetoothScoOn(true);
//                    audioManager.setMicrophoneMute(false);

                    startRecorder(promise);
                    reactContext.unregisterReceiver(this);
//                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                audioManager.startBluetoothSco();
            }
        };
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        reactContext.registerReceiver(receiver, filter);
    }

    private void startRecorder(Promise promise) {
        mediaRecorder.start();
        long systemTime = SystemClock.elapsedRealtime();
        recorderRunnable = new Runnable() {
            @Override
            public void run() {
                long time = SystemClock.elapsedRealtime() - systemTime - totalPausedRecordTime;
                WritableMap obj = Arguments.createMap();
                obj.putDouble("currentPosition", time);
                if (_meteringEnabled) {
                    int maxAmplitude = 0;
                    if (mediaRecorder != null) {
                        maxAmplitude = mediaRecorder.getMaxAmplitude();
                    }
                    double dB = 0.0;
                    if (maxAmplitude > 0) {
                        dB = maxAmplitude / 32767.0;
//                            dB = 20 * log10(level)
                    }
                    obj.putDouble("currentMetering", dB);
                }
                sendEvent(reactContext, "rn-recordback", obj);
                recordHandler.postDelayed(recorderRunnable, subsDurationMillis);
            }
        };

        recorderRunnable.run();
        promise.resolve("file://" + audioFileURL);
    }

    @ReactMethod
    public void resumeRecorder(Promise promise) {
        if (mediaRecorder == null) {
            promise.reject("resumeReocrder", "Recorder is null.");
            return;
        }

        try {
            mediaRecorder.resume();
            totalPausedRecordTime += SystemClock.elapsedRealtime() - pausedRecordTime;
            if (recorderRunnable != null)
                recordHandler.postDelayed(recorderRunnable, subsDurationMillis);
            promise.resolve("Recorder resumed.");
        } catch (Exception e) {
            Log.e(tag, "Recorder resume: " + e.getMessage());
            promise.reject("resumeRecorder", e.getMessage());
        }
    }

    @ReactMethod
    public void pauseRecorder(Promise promise) {
        if (mediaRecorder == null) {
            promise.reject("pauseRecorder", "Recorder is null.");
            return;
        }

        try {
            mediaRecorder.pause();
            pausedRecordTime = SystemClock.elapsedRealtime();
            if (recorderRunnable != null)
                recordHandler.removeCallbacks(recorderRunnable);
            promise.resolve("Recorder paused.");
        } catch (Exception e) {
            Log.e(tag, "pauseRecorder exception: " + e.getMessage());
            promise.reject("pauseRecorder", e.getMessage());
        }
    }

    @ReactMethod
    public void stopRecorder(Promise promise) {


        if (recorderRunnable != null) {
            recordHandler.removeCallbacks(recorderRunnable);
        }

        if (mediaRecorder == null) {
            promise.reject("stopRecord", "recorder is null.");
            return;
        }

        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
            if (audioManager.isBluetoothScoOn()) {
                audioManager.abandonAudioFocus(afChangeListener);
                audioManager.setBluetoothScoOn(false);

                audioManager.stopBluetoothSco();
//                audioManager.setBluetoothA2dpOn(true);
//                audioManager.setStreamSolo(AudioManager.STREAM_MUSIC, true);
//                audioManager.setRouting(AudioManager.MODE_NORMAL, AudioManager.ROUTE_BLUETOOTH_A2DP, AudioManager.ROUTE_BLUETOOTH);
            }

            promise.resolve("file://" + audioFileURL);

        } catch (RuntimeException stopException) {

            promise.reject("stopRecord", stopException.getMessage());
        }
    }

    @ReactMethod
    public void setVolume(Double volume, Promise promise) {
        if (mediaPlayer == null) {
            promise.reject("setVolume", "player is null.");
            return;
        }

        float mVolume = (float) (volume / 1.0);
        mediaPlayer.setVolume(mVolume, mVolume);
        promise.resolve("set volume");
    }

    @ReactMethod
    public void startPlayer(String path, ReadableMap httpHeaders, Promise promise) {
        if (mediaPlayer != null) {
            boolean isPaused = !mediaPlayer.isPlaying() && mediaPlayer.getCurrentPosition() > 1;

            if (isPaused) {
                mediaPlayer.start();
                promise.resolve("player resumed.");
                return;
            }

            Log.e(tag, "Player is already running. Stop it first.");
            promise.reject("startPlay", "Player is already running. Stop it first.");
            return;
        } else {
            mediaPlayer = new MediaPlayer();
        }

        try {
            if ("DEFAULT".equals(path)) {
                mediaPlayer.setDataSource(reactContext.getCacheDir() + "/" + defaultFileName);
            } else {
                if (httpHeaders != null) {
                    Map<String, String> headers = new HashMap<>();
                    ReadableMapKeySetIterator iterator = httpHeaders.keySetIterator();
                    while (iterator.hasNextKey()) {
                        String key = iterator.nextKey();
                        headers.put(key, httpHeaders.getString(key));
                    }
                    mediaPlayer.setDataSource(reactContext, Uri.parse(path), headers);
                } else {
                    mediaPlayer.setDataSource(path);
                }
            }
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    Log.d(tag, "Mediaplayer prepared and start");
                    mp.start();
                    /**
                     * Set timer task to send event to RN.
                     */
                    mTask = new TimerTask() {
                        @Override
                        public void run() {
                            try {
                                WritableMap obj = Arguments.createMap();
                                obj.putInt("duration", mp.getDuration());
                                obj.putInt("currentPosition", mp.getCurrentPosition());
                                sendEvent(reactContext, "rn-playback", obj);
                            } catch (IllegalStateException e) {
                                Log.e(tag, "Mediaplayer error: " + e.getMessage());
                            }
                        }
                    };

                    mTimer = new Timer();
                    mTimer.schedule(mTask, 0, subsDurationMillis);
                    String resolvedPath = "DEFAULT".equals(path) ? reactContext.getCacheDir() + "/" + defaultFileName : path;

                    promise.resolve(resolvedPath);
                }
            });

            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    WritableMap obj = Arguments.createMap();
                    obj.putInt("duration", mp.getDuration());
                    obj.putInt("currentPosition", mp.getCurrentPosition());
                    sendEvent(reactContext, "rn-playback", obj);
                    /**
                     * Reset player.
                     */
                    Log.d(tag, "Plays completed.");
                    mTimer.cancel();
                    mp.stop();
                    mp.reset();
                    mp.release();
                    mediaPlayer = null;
                }
            });

            mediaPlayer.prepare();
        } catch (IOException e) {
            Log.e(tag, "startPlay() io exception");
            promise.reject("startPlay", e.getMessage());
        } catch (NullPointerException e) {
            Log.e(tag, "startPlay() null exception");
        }
    }

    @ReactMethod
    public void resumePlayer(Promise promise) {
        if (mediaPlayer == null) {
            promise.reject("resume", "Mediaplayer is null on resume.");
            return;
        }

        if (mediaPlayer.isPlaying()) {
            promise.reject("resume", "Mediaplayer is already running.");
            return;
        }

        try {
            mediaPlayer.seekTo(mediaPlayer.getCurrentPosition());
            mediaPlayer.start();
            promise.resolve("resume player");
        } catch (Exception e) {
            Log.e(tag, "Mediaplayer resume: " + e.getMessage());
            promise.reject("resume", e.getMessage());
        }
    }

    @ReactMethod
    public void pausePlayer(Promise promise) {
        if (mediaPlayer == null) {
            promise.reject("pausePlay", "Mediaplayer is null on pause.");
            return;
        }

        try {
            mediaPlayer.pause();
            promise.resolve("pause player");
        } catch (Exception e) {
            Log.e(tag, "pausePlay exception: " + e.getMessage());
            promise.reject("pausePlay", e.getMessage());
        }
    }

    @ReactMethod
    public void seekToPlayer(Double time, Promise promise) {
        if (mediaPlayer == null) {
            promise.reject("seekTo", "Mediaplayer is null on seek.");
            return;
        }

        mediaPlayer.seekTo((int) (time / 1));
        promise.resolve("pause player");
    }

    @ReactMethod
    public void stopPlayer(Promise promise) {
        if (mTimer != null) {
            mTimer.cancel();
        }

        if (mediaPlayer == null) {
            promise.resolve("Already stopped player");
            return;
        }

        try {
            mediaPlayer.stop();
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
            promise.resolve("stopped player");
        } catch (Exception e) {
            Log.e(tag, "stopPlay exception: " + e.getMessage());
            promise.reject("stopPlay", e.getMessage());
        }
    }

    @ReactMethod
    public void setSubscriptionDuration(Double sec, Promise promise) {
        subsDurationMillis = (int) (sec * 1000);
        promise.resolve("setSubscriptionDuration: " + subsDurationMillis);
    }

    private void sendEvent(ReactContext reactContext,
                           String eventName,
                           WritableMap params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }
}
