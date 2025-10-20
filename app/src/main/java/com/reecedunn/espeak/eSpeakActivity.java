package com.reecedunn.espeak;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.io.File;
import java.io.IOException;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.AdapterView;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;
import android.widget.ImageButton;
import java.util.ArrayList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
public class eSpeakActivity extends Activity {
    private ImageView logoShape;
    private static final String TAG = "eSpeakActivity";

    private Model voskSpeechModel;
    private SpeechService voskService;
    private SpeechSynthesis ttsEspeakEngine;
    private PowerManager.WakeLock wakeLock;
    private Translator translator;

    private boolean translatorReady = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean translationRunning = false;

    private String lastSpoken = "";
    private long lastSpeakTime = 0;

    private TextProcessor textProcessor;
    private TextView txtRecognized, txtTranslated, txtVoskRaw, txtPitchLabel;
    private Button btnMic, btnPartial, btnFinal, btnSmartClip;

    private Spinner spnSpeed, spnWordCount1, spnWordCount2, spnLanguage;
    private Spinner spnEngineSelect;
    private String selectedEngine; // "vosk" or "google"
    private SeekBar seekPitch;

    private int selectedSpeed;
    private int selectedPitch;
    private int wordCount1;
    private int wordCount2;
    private String selectedTargetLanguage;
    private final String PREFS = "settings";

    private final ExecutorService ttsExecutor = Executors.newFixedThreadPool(1);
    private final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();
    private boolean keepGoogleListening = false;
    private SpeechRecognizer googleRecognizer;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 101);
            return; // stop until permission granted
        }

        logoShape = findViewById(R.id.logoShape);
        // 🟣 Tap animation on logo
        logoShape.setOnClickListener(v -> {
            v.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.icon_tap));
        });

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedSpeed = prefs.getInt("speed", 320);
        selectedPitch = prefs.getInt("pitch", 55);
        wordCount1 = prefs.getInt("wordCount1", 3);
        wordCount2 = prefs.getInt("wordCount2", 7);
        selectedTargetLanguage = prefs.getString("lang", TranslateLanguage.PERSIAN);
        selectedEngine = prefs.getString("engine", "vosk");
        if (selectedEngine == null) selectedEngine = "vosk";

        boolean clipState = prefs.getBoolean("clipEnabled", true);

        txtRecognized = findViewById(R.id.txtRecognized);
        txtTranslated = findViewById(R.id.txtTranslated);
        txtTranslated.setText("");

        txtVoskRaw = findViewById(R.id.txtVoskRaw);

        btnPartial = findViewById(R.id.btnPartial);
        btnFinal = findViewById(R.id.btnFinal);
        btnSmartClip = findViewById(R.id.btnSmartClip);
        spnSpeed = findViewById(R.id.spnSpeed);
        spnWordCount1 = findViewById(R.id.spnWordCount1);
        spnWordCount2 = findViewById(R.id.spnWordCount2);
        spnLanguage = findViewById(R.id.spnLanguage);
        spnEngineSelect = findViewById(R.id.spnEngineSelect);
        txtPitchLabel = findViewById(R.id.txtPitchLabel);
        seekPitch = findViewById(R.id.seekPitch);


        // 🔹 Top control icons
        ImageButton btnMenu = findViewById(R.id.btnMenu);
        ImageButton btnBack = findViewById(R.id.btnBack);
        LinearLayout settingsContainer = findViewById(R.id.settingsContainer);

        // 🍔 Hamburger → opens settings
        btnMenu.setOnClickListener(v -> {
            settingsContainer.setVisibility(View.VISIBLE);
            btnMenu.setVisibility(View.GONE);
            btnBack.setVisibility(View.VISIBLE);
        });

        // 🔙 Back → closes settings
        btnBack.setOnClickListener(v -> {
            settingsContainer.setVisibility(View.GONE);
            btnBack.setVisibility(View.GONE);
            btnMenu.setVisibility(View.VISIBLE);
        });

        // 🧠 Text Processor setup
        textProcessor = new TextProcessor(txtRecognized);
        textProcessor.setClipEnabled(clipState);
        textProcessor.setWordRange(wordCount1, wordCount2);

        updateButtonState(btnPartial, true, "🧩 Partial Mode: ");
        updateButtonState(btnFinal, true, "✅ Final Mode: ");
        updateButtonState(btnSmartClip, clipState, "✂ Smart Clip (Final Only): ");

        btnPartial.setOnClickListener(v ->
                toggleButton(btnPartial, "🧩 Partial Mode: ", prefs, "partial", textProcessor::setPartialEnabled));

        btnFinal.setOnClickListener(v ->
                toggleButton(btnFinal, "✅ Final Mode: ", prefs, "final", textProcessor::setFinalEnabled));

        btnSmartClip.setOnClickListener(v -> {
            boolean newState = !textProcessor.isClipEnabled();
            textProcessor.setClipEnabled(newState);
            updateButtonState(btnSmartClip, newState, "✂ Smart Clip (Final Only): ");
            prefs.edit().putBoolean("clipEnabled", newState).apply();
        });

        // 🗣 Initialize systems
        initializeTTSSpeakEngine();
        initTranslator();
        initVosk();
// 🌊 Start gentle pulse animation while initializing
        logoShape.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.icon_pulse));

// Automatically start listening after short delay
        new android.os.Handler().postDelayed(() -> {
            isListening = true;
            if ("google".equals(selectedEngine))startGoogleSTT();
            else startVoskListening();
            toast("🎤 Auto listening started");
            logoShape.clearAnimation();

        }, 1500);


        // ⚡ Prevent device from sleeping
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eSpeakApp::Lock");
        wakeLock.acquire();


        // 🔄 Restore spinner selections
        spnSpeed.setSelection(Math.max(0, (selectedSpeed - 250) / 20));
        spnWordCount1.setSelection(wordCount1 - 1);
        spnWordCount2.setSelection(wordCount2 - 2);
        spnLanguage.setSelection(langToIndex(selectedTargetLanguage));
        spnEngineSelect.setSelection(selectedEngine.equals("google") ? 1 : 0);

        // 🎛 Pitch control
        seekPitch.setProgress(selectedPitch);
        txtPitchLabel.setText("Pitch: " + selectedPitch);
        seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedPitch = Math.max(20, Math.min(progress, 100));
                txtPitchLabel.setText("Pitch: " + selectedPitch);
                prefs.edit().putInt("pitch", selectedPitch).apply();
                if (ttsEspeakEngine != null) ttsEspeakEngine.Pitch.setValue(selectedPitch);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 🎚 Speed control
        spnSpeed.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                selectedSpeed = Integer.parseInt(parent.getItemAtPosition(position).toString());
                if (ttsEspeakEngine != null) ttsEspeakEngine.Rate.setValue(selectedSpeed);
                prefs.edit().putInt("speed", selectedSpeed).apply();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 🧩 Word ranges
        spnWordCount1.setOnItemSelectedListener(wordListener(true, prefs));
        spnWordCount2.setOnItemSelectedListener(wordListener(false, prefs));

        // 🌐 Language selection
        spnLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                selectedTargetLanguage = indexToLang(position);
                prefs.edit().putString("lang", selectedTargetLanguage).apply();
                initTranslator();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 🎙 Engine selection
        spnEngineSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                selectedEngine = (position == 1) ? "google" : "vosk";
                prefs.edit().putString("engine", selectedEngine).apply();
                toast("Engine: " + (selectedEngine.equals("google") ? "Google STT" : "Vosk Offline"));
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private AdapterView.OnItemSelectedListener wordListener(boolean isMin, SharedPreferences prefs) {
        return new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                int val = Integer.parseInt(parent.getItemAtPosition(position).toString());
                if (isMin) wordCount1 = val; else wordCount2 = val;
                textProcessor.setWordRange(wordCount1, wordCount2);
                prefs.edit().putInt(isMin ? "wordCount1" : "wordCount2", val).apply();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    private void initVosk() {
        StorageService.unpack(this, "model-en-us", "model",
                model -> {
                    voskSpeechModel = model;
                    toast("✅ Model loaded");
                    if (!isListening && selectedEngine.equals("vosk")) {
                        startVoskListening();
                    }
                },
                e -> toast("❌ Model load failed: " + e.getMessage()));

    }
    @Override
    protected void onResume() {
        super.onResume();
        if (!isListening) {
            isListening = true;
            if ("google".equals(selectedEngine)) startGoogleSTT();
            else startVoskListening();
        }
    }

    private void startVoskListening() {
        if (voskSpeechModel == null) { toast("❌ Model not ready"); return; }

        try {
            Recognizer rec = new Recognizer(voskSpeechModel, 16000.0f);
            voskService = new SpeechService(rec, 16000.0f);
            voskService.startListening(new RecognitionListener() {

                public void onPartialResult(String hyp) {
                    appendRaw("Partial: " + hyp);
                    textProcessor.handlePartial(hyp);
                    handleNewPhrase(textProcessor.getLastPrinted());
                }

                public void onResult(String hyp) {
                    appendRaw("Result: " + hyp);
                    textProcessor.handleFinal(hyp);
                    handleNewPhrase(textProcessor.getLastPrinted());
                }

                public void onFinalResult(String hyp) {
                    appendRaw("Final: " + hyp);
                    textProcessor.handleFinal(hyp);
                    handleNewPhrase(textProcessor.getLastPrinted());
                }

                public void onError(Exception e) { appendRaw("Error: " + e.getMessage()); }
                public void onTimeout() {}
            });
// 🎵 Simulated pulse while Vosk listens
            new Thread(() -> {
                while (isListening && voskService != null) {
                    float fakeRms = (float) (Math.random() * 6 + 4); // soft random movement
                    updateMicAnimation(fakeRms);
                    Log.d("MicRMS", "RMS: " + fakeRms);

                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }).start();

        } catch (IOException e) { toast("Start failed: " + e.getMessage()); }
    }

    private void startGoogleSTT() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("❌ Google Speech not available");
            return;
        }

        if (googleRecognizer != null) {
            googleRecognizer.destroy();
        }

        googleRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        keepGoogleListening = true;

        googleRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    txtRecognized.setText(text);
                    translateAndSpeakInstant(text);
                }

                // 🔁 Restart listening after final result
                if (keepGoogleListening) restartGoogleSTT();
            }

            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty())
                    txtRecognized.setText(partial.get(0));
            }

            @Override public void onError(int error) {
                // If an error like silence timeout happens, restart automatically
                if (keepGoogleListening) restartGoogleSTT();
            }

            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override
            public void onRmsChanged(float rmsdB) {
                updateMicAnimation(rmsdB);
            }


            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        googleRecognizer.startListening(intent);
        toast("🎤 Google STT (Offline if installed) started");
    }

    private void restartGoogleSTT() {
        if (!keepGoogleListening) return;

        // reset the pulse animation each time
        runOnUiThread(() -> {
            logoShape.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        });

        if (googleRecognizer != null) {
            try {
                googleRecognizer.destroy();
            } catch (Exception ignored) {}
            googleRecognizer = null;
        }

        // wait a bit before re-creating recognizer
        txtRecognized.postDelayed(() -> {
            googleRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

            googleRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        txtRecognized.setText(text);
                        translateAndSpeakInstant(text);
                    }
                    if (keepGoogleListening) txtRecognized.postDelayed(this::restart, 800);
                }

                private void restart() { restartGoogleSTT(); }

                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty())
                        txtRecognized.setText(partial.get(0));
                }

                @Override public void onError(int error) {
                    if (keepGoogleListening)
                        txtRecognized.postDelayed(this::restart, 1000); // wait longer between retries
                }

                @Override public void onRmsChanged(float rmsdB) {
                    runOnUiThread(() -> {
                        float scale = 1f + (rmsdB / 10f);
                        scale = Math.max(1f, Math.min(scale, 1.3f));
                        logoShape.animate()
                                .scaleX(scale)
                                .scaleY(scale)
                                .setDuration(100)
                                .start();
                    });
                }

                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });

            googleRecognizer.startListening(intent);
        }, 600); // <-- add 600ms delay to stabilize
    }


    private void handleNewPhrase(String phrase) {
        if (phrase.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!phrase.equalsIgnoreCase(lastSpoken) && now - lastSpeakTime > 1500) {
            lastSpoken = phrase;
            lastSpeakTime = now;
            translateAndSpeakInstant(phrase);
        }
    }

    private void initTranslator() {
        String sourceLang = selectedEngine.equals("google")
                ? TranslateLanguage.GERMAN   // Google STT uses German
                : TranslateLanguage.ENGLISH;  // Vosk uses English

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(selectedTargetLanguage) // usually Farsi
                .build();

        if (translator != null) translator.close();
        translator = Translation.getClient(options);
        translator.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> translatorReady = true)
                .addOnFailureListener(e -> toast("❌ Translator init failed"));
    }


    private void translateAndSpeakInstant(String phrase) {
        if (!translatorReady || phrase.isEmpty() || translationRunning) return;
        translationRunning = true;

        translationExecutor.execute(() -> {
            translator.translate(phrase)
                    .addOnSuccessListener(translated -> {
                        runOnUiThread(() -> txtTranslated.setText(translated));
                        speakTranslated(translated);
                    })
                    .addOnCompleteListener(task -> translationRunning = false)
                    .addOnFailureListener(e -> translationRunning = false);
        });
    }

    private void initializeTTSSpeakEngine() {
        try {
            CheckVoiceData.installVoiceDataIfMissing(this);
            ttsEspeakEngine = new SpeechSynthesis(this, null);
            File dir = CheckVoiceData.getDataPath(this);
            ttsEspeakEngine.nativeCreate(dir.getAbsolutePath());
            ttsEspeakEngine.nativeSetVoiceByName("fa");
            ttsEspeakEngine.Rate.setValue(selectedSpeed);
            ttsEspeakEngine.Pitch.setValue(selectedPitch);
        } catch (Exception e) { Log.e(TAG, "Engine init failed", e); }
    }

    private void speakTranslated(String text) {
        if (ttsEspeakEngine == null || text.isEmpty() || isSpeaking) return;
        isSpeaking = true;
        ttsExecutor.execute(() -> {
            try {
                String voiceCode = selectedTargetLanguage.equals(TranslateLanguage.HINDI) ? "hi" :
                        selectedTargetLanguage.equals(TranslateLanguage.ENGLISH) ? "en" : "fa";
                ttsEspeakEngine.nativeSetVoiceByName(voiceCode);
                ttsEspeakEngine.Pitch.setValue(selectedPitch);
                ttsEspeakEngine.synthesize(text, false);
            } catch (Exception e) { Log.e(TAG, "Speak failed: " + e.getMessage()); }
            finally { isSpeaking = false; }
        });
    }

    private void toggleLiveTranslate() {
        if (!isListening) {
            isListening = true;
            btnMic.setBackgroundColor(getResources().getColor(R.color.live_active));
            btnMic.setText("🛑 Stop Live Translate");

            if ("google".equals(selectedEngine)) startGoogleSTT();
            else startVoskListening();

            toast("🎤 Listening started");
        } else {
            isListening = false;
            btnMic.setBackgroundColor(getResources().getColor(R.color.live_inactive));
            btnMic.setText("🎙 Live Translate");
            stopEverything();
            logoShape.setScaleX(1f);
            logoShape.setScaleY(1f);

            toast("🛑 Listening stopped");
        }
    }

    private void stopEverything() {
        if (googleRecognizer != null) {
            keepGoogleListening = false;
            googleRecognizer.cancel();
            googleRecognizer.destroy();
            googleRecognizer = null;
        }

    }
    private void updateMicAnimation(float rmsdB) {
        runOnUiThread(() -> {
            float scale = 1f + (rmsdB / 10f);
            scale = Math.max(1f, Math.min(scale, 1.5f));
            logoShape.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(100)
                    .start();
        });
    }

    private void updateButtonState(Button btn, boolean enabled, String label) {
        int color = getResources().getColor(enabled ? R.color.live_active : R.color.live_inactive);
        btn.setBackgroundColor(color);
        btn.setText(label + (enabled ? "ON" : "OFF"));
    }

    private void toggleButton(Button btn, String label, SharedPreferences prefs, String key, java.util.function.Consumer<Boolean> setter) {
        boolean newState = !(btn.getText().toString().endsWith("ON"));
        setter.accept(newState);
        updateButtonState(btn, newState, label);
        prefs.edit().putBoolean(key, newState).apply();
    }

    private void appendRaw(String msg) {
        runOnUiThread(() -> {
            String existing = txtVoskRaw.getText().toString();
            // 🧩 Newest line first, old ones move down
            String updated = msg + "\n" + existing;

            txtVoskRaw.setText(updated);

            // optional: keep it tidy — limit to 2000 chars
            if (updated.length() > 2000) {
                txtVoskRaw.setText(updated.substring(0, 2000));
            }
        });
    }

    private void toast(String msg) { runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()); }

    private int langToIndex(String lang) {
        switch (lang) {
            case TranslateLanguage.HINDI: return 1;
            case TranslateLanguage.ENGLISH: return 2;
            default: return 0;
        }
    }

    private String indexToLang(int idx) {
        switch (idx) {
            case 1: return TranslateLanguage.HINDI;
            case 2: return TranslateLanguage.ENGLISH;
            default: return TranslateLanguage.PERSIAN;
        }
    }
    @Override
    protected void onStop() {
        super.onStop();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopEverything();
        if (translator != null) translator.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        translationExecutor.shutdownNow();
        ttsExecutor.shutdownNow();
    }

    @Override protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && grantResults.length > 0 && grantResults[0] ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            recreate(); // restart activity to proceed normally
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show();
            finish();
        }
    }


}  