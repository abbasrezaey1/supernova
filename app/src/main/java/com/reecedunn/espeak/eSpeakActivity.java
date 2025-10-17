/*
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Modified in 2025 for self-contained synthesis + speech translation (no Android TTS dependency)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.reecedunn.espeak;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import android.content.Intent;

public class eSpeakActivity extends Activity {
    private static final String TAG = "eSpeakActivity";
    private static final int REQ_MIC = 101;

    private enum State { LOADING, ERROR, SUCCESS }

    private State mState;
    private SpeechSynthesis mSynth;
    private Translator translator;
    private SpeechRecognizer recognizer;

    private List<Pair<String, String>> mInformation;
    private InformationListAdapter mInformationView;
    private EditText mText;
    private TextView txtRecognized;
    private TextView txtTranslated;
    private ProgressBar progressBar;
    private boolean isListening = false;
    private boolean continuous = true;
    private final Handler handler = new Handler();
    private String lastWord = null;
    private long lastSpeakTime = 0L;
    // ---------------- LIFECYCLE ----------------
    private final List<String> wordBuffer = new ArrayList<>();
    private static final int WORD_CHUNK_SIZE = 3;
    private String lastPartial = "";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Bind UI
        mInformation = new ArrayList<>();
        mInformationView = new InformationListAdapter(this, mInformation);
        ((ListView) findViewById(R.id.properties)).setAdapter(mInformationView);

        mText = findViewById(R.id.editText1);
        txtRecognized = findViewById(R.id.txtRecognized);
        txtTranslated = findViewById(R.id.txtTranslated);
        progressBar = findViewById(R.id.progressBar);

        setState(State.LOADING);
        installVoiceDataIfMissing(this);
        initializeEngine();
        initTranslator();
        initRecognizer();

        // 🔊 Speak typed text
        Button speak = findViewById(R.id.speak);
        speak.setOnClickListener(v -> {
            if (mSynth != null) {
                String text = mText.getText().toString().trim();
                if (!text.isEmpty()) {
                    String normalized = normalizeFarsi(text);
                    setPersianVoice();
                    mSynth.synthesize(normalized, false);
                }
            }
        });

        // 🧩 Insert SSML
        Button ssml = findViewById(R.id.ssml);
        ssml.setOnClickListener(v -> {
            String ssmlText =
                    "<?xml version=\"1.0\"?>\n" +
                            "<speak xmlns=\"http://www.w3.org/2001/10/synthesis\" version=\"1.0\">\n" +
                            "  <p>Hallo, dies ist eSpeak, das selbst spricht!</p>\n" +
                            "</speak>";
            mText.setText(ssmlText);
        });

        // 🎙 Mic button
        Button btnMic = findViewById(R.id.btnMic);
        btnMic.setOnClickListener(v -> ensureMicPermission());

        // 🧭 Open Vosk demo screen
        Button openVosk = findViewById(R.id.open_vosk);
        openVosk.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this, VoskActivity.class);
            startActivity(intent);
        });


    }

    @Override
    protected void onStart() {
        super.onStart();
        setState(State.SUCCESS);
        populateInformationView();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mSynth != null) mSynth.stop();
        if (recognizer != null) recognizer.stopListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (translator != null) translator.close();
        if (recognizer != null) recognizer.destroy();
        if (mSynth != null) mSynth.stop();
    }

    // ---------------- TRANSLATOR ----------------



    private void initTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.GERMAN)
                .setTargetLanguage(TranslateLanguage.PERSIAN)
                .build();
        translator = Translation.getClient(options);

        translator.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> toast("✅ Translator ready (DE → FA)"))
                .addOnFailureListener(e -> toast("❌ Translator init failed"));
    }
    private void translateAndSpeakInstant(String germanWord) {
        long now = System.currentTimeMillis();
        if (now - lastSpeakTime < 600) return; // small delay limiter
        lastSpeakTime = now;

        translator.translate(germanWord)
                .addOnSuccessListener(farsi -> {
                    String normalized = normalizeFarsi(farsi);
                    runOnUiThread(() -> {
                        txtTranslated.append(normalized + " ");
                    });

                    if (mSynth != null) {
                        setPersianVoice();
                        mSynth.synthesize(normalized, false);
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "Instant translation failed: " + e.getMessage()));
    }
    // ---------------- RECOGNIZER ----------------

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition not available ❌");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { toast("🎤 Ready — speak now…"); }
            @Override public void onBeginningOfSpeech() { toast("🎧 Listening…"); }
            @Override public void onRmsChanged(float rmsdB) {
                int level = Math.min(Math.max((int) rmsdB, 0), 12);
                progressBar.setProgress(level);
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { toast("🌀 Processing…"); }

            @Override
            public void onError(int error) {
                Log.w(TAG, "Speech error: " + error);
                isListening = false;
                restartRecognizerAfterDelay();
            }


            @Override
            public void onPartialResults(Bundle partialResults) {
                List<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list == null || list.isEmpty()) return;

                String currentGerman = list.get(0).trim();
                runOnUiThread(() -> txtRecognized.setText(currentGerman));
                processBufferedWords(currentGerman);
            }


            @Override
            public void onResults(Bundle results) {
                List<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    String finalGerman = list.get(0);
                    handleFinalResult(finalGerman);
                }
                isListening = false;
                if (continuous) restartRecognizerAfterDelay();
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    // ---------------- SPEECH FLOW ----------------
    private void processBufferedWords(String currentGerman) {
        if (currentGerman.equalsIgnoreCase(lastPartial)) return; // prevent repeats
        lastPartial = currentGerman;

        String[] words = currentGerman.split("\\s+");
        if (words.length == 0) return;

        // get the last recognized word
        String lastWord = words[words.length - 1];

        // if it's new, add to buffer
        if (wordBuffer.isEmpty() || !wordBuffer.get(wordBuffer.size() - 1).equalsIgnoreCase(lastWord)) {
            wordBuffer.add(lastWord);
        }

        // speak every 3 words
        if (wordBuffer.size() >= WORD_CHUNK_SIZE) {
            String chunk = String.join(" ", wordBuffer);
            wordBuffer.clear(); // reset buffer
            translateAndSpeakChunk(chunk);
        }
    }

    private void translateAndSpeakChunk(String germanChunk) {
        translator.translate(germanChunk)
                .addOnSuccessListener(farsi -> {
                    String normalized = normalizeFarsi(farsi);
                    runOnUiThread(() -> txtTranslated.append(normalized + " "));
                    if (mSynth != null) {
                        setPersianVoice();
                        mSynth.synthesize(normalized, false);
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "Chunk translation failed: " + e.getMessage()));
    }

    private void handleFinalResult(String germanText) {
        runOnUiThread(() -> txtRecognized.setText(germanText));
        translator.translate(germanText)
                .addOnSuccessListener(farsi -> {
                    String normalized = normalizeFarsi(farsi);
                    runOnUiThread(() -> txtTranslated.setText(normalized));
                    if (mSynth != null) {
                        setPersianVoice();
                        mSynth.synthesize(normalized, false);
                    }
                })
                .addOnFailureListener(e -> toast("❌ Translation failed: " + e.getMessage()));
    }

    private void restartRecognizerAfterDelay() {
        handler.postDelayed(() -> {
            if (!isListening) startListening();
        }, 1000);
    }

    private void startListening() {
        if (isListening) return;
        isListening = true;
        txtRecognized.setText("");
        txtTranslated.setText("");
        android.content.Intent intent = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        recognizer.startListening(intent);
    }

    private void ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else startListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startListening();
        else toast("❌ Microphone permission denied");
    }

    // ---------------- ESPEAK ENGINE ----------------

    private void initializeEngine() {
        try {
            CheckVoiceData.installVoiceDataIfMissing(this);
            mSynth = new SpeechSynthesis(this, null);
            File dataDir = CheckVoiceData.getDataPath(this);
            mSynth.nativeCreate(dataDir.getAbsolutePath());

            // Log available voices to find the Persian one
            List<Voice> voices = mSynth.getAvailableVoices();
            for (Voice v : voices) {
                Log.i(TAG, "Voice available → " + v.name + " | ID=" + v.identifier);
            }

            Log.i(TAG, "eSpeak engine initialized ✅");
            setState(State.SUCCESS);
        } catch (Exception e) {
            Log.e(TAG, "Engine initialization failed: " + e.getMessage(), e);
            setState(State.ERROR);
        }
    }

    private void setPersianVoice() {
        try {
            boolean ok = mSynth.nativeSetVoiceByName("fa+fa1");
            if (!ok) {
                ok = mSynth.nativeSetVoiceByName("fa");
            }
            Log.i(TAG, "Switching to Persian voice (fa/fa1): " + ok);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set Persian voice: " + e.getMessage());
        }
    }

    private void installVoiceDataIfMissing(Context context) {
        File dataPath = CheckVoiceData.getDataPath(context);
        if (CheckVoiceData.hasBaseResources(context)) {
            Log.v(TAG, "eSpeak data already installed");
            return;
        }
        try {
            AssetManager am = context.getAssets();
            copyAssetFolder(am, "espeak-ng-data", dataPath.getAbsolutePath());
            Log.v(TAG, "eSpeak voice data installed ✅");
        } catch (Exception e) {
            Log.e(TAG, "Voice data installation failed: " + e.getMessage(), e);
        }
    }

    private static boolean copyAssetFolder(AssetManager am, String from, String to) throws IOException {
        String[] assets = am.list(from);
        if (assets == null) return false;
        File dir = new File(to);
        if (!dir.exists()) dir.mkdirs();
        for (String asset : assets) {
            String subFrom = from + "/" + asset;
            String subTo = to + "/" + asset;
            String[] list = am.list(subFrom);
            if (list != null && list.length > 0) copyAssetFolder(am, subFrom, subTo);
            else copyAsset(am, subFrom, subTo);
        }
        return true;
    }

    private static void copyAsset(AssetManager am, String from, String to) throws IOException {
        try (InputStream in = am.open(from);
             OutputStream out = new FileOutputStream(new File(to))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    // ---------------- HELPERS ----------------

    private String normalizeFarsi(String input) {
        if (input == null) return "";
        return input.replace('ي', 'ی').replace('ك', 'ک');
    }

    private void populateInformationView() {
        mInformation.clear();
        mInformation.add(new Pair<>("Available voices", Integer.toString(SpeechSynthesis.getVoiceCount())));
        mInformation.add(new Pair<>("Version", SpeechSynthesis.getVersion()));
        mInformation.add(new Pair<>("Status", "eSpeak active (self-contained + translation)"));
        mInformationView.notifyDataSetChanged();
    }

    private void setState(State state) {
        mState = state;
        findViewById(R.id.loading).setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE);
        findViewById(R.id.success).setVisibility(state == State.SUCCESS ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }


}