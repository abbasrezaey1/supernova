/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reecedunn.espeak;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech.Engine;
import android.util.Log;

import com.reecedunn.espeak.SpeechSynthesis.SynthReadyCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

public class CheckVoiceData extends Activity {
    private static final String TAG = "eSpeakTTS";

    /** Resources required for eSpeak to run correctly. */
    private static final String[] BASE_RESOURCES = {
            "version",
            "intonations",
            "phondata",
            "phonindex",
            "phontab",
            "en_dict"
    };

    // ------------------------------------------------------------------------
    // 1.  Path + Resource Checks
    // ------------------------------------------------------------------------

    public static File getDataPath(Context context) {
        return new File(context.getDir("voices", MODE_PRIVATE), "espeak-ng-data");
    }

    public static boolean hasBaseResources(Context context) {
        File dataPath = getDataPath(context);
        File voicesDir = new File(dataPath, "voices");
        File langDir = new File(dataPath, "lang");

        if (voicesDir.exists() && langDir.exists()) {
            Log.v(TAG, "Base resources found (voices/lang).");
            return true;
        }

        Log.e(TAG, "Missing voices or lang folder in " + dataPath.getAbsolutePath());
        return false;
    }


    public static boolean canUpgradeResources(Context context) {
        return false;
    }


    // ------------------------------------------------------------------------
    // 2.  Installer — copies espeak-ng-data from assets if missing
    // ------------------------------------------------------------------------

    public static void installVoiceDataIfMissing(Context context) {
        File dataPath = getDataPath(context);
        if (hasBaseResources(context)) {
            Log.v("eSpeakTTS", "eSpeak base data present.");
            return;
        }

        Log.v("eSpeakTTS", "Installing missing eSpeak voice data...");
        dataPath.mkdirs();

        try {
            // Copy espeakdata.zip from res/raw to app_voices/
            InputStream is = context.getResources().openRawResource(R.raw.espeakdata);
            File zipFile = new File(dataPath.getParentFile(), "espeakdata.zip");
            FileOutputStream fos = new FileOutputStream(zipFile);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
            fos.close();
            is.close();

            // Unzip it
            unzip(zipFile, dataPath);


            zipFile.delete(); // remove zip after extraction
            Log.v("eSpeakTTS", "Installation complete!");
        } catch (Exception e) {
            Log.e("eSpeakTTS", "Installation failed: " + e.getMessage(), e);
        }
    }

    /** Helper: unzip espeakdata.zip */
    private static void unzip(File zipFile, File targetDir) throws IOException {
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile));
        java.util.zip.ZipEntry entry;
        byte[] buffer = new byte[4096];
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(targetDir, entry.getName());
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                int len;
                while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                fos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }


    private static boolean copyAssetFolder(AssetManager assetManager, String from, String to) throws IOException {
        String[] assets = assetManager.list(from);
        if (assets == null || assets.length == 0) return false;
        File dir = new File(to);
        if (!dir.exists()) dir.mkdirs();
        for (String asset : assets) {
            String subFrom = from + "/" + asset;
            String subTo = to + "/" + asset;
            String[] list = assetManager.list(subFrom);
            if (list != null && list.length > 0) {
                copyAssetFolder(assetManager, subFrom, subTo);
            } else {
                copyAsset(assetManager, subFrom, subTo);
            }
        }
        return true;
    }

    private static void copyAsset(AssetManager assetManager, String from, String to) throws IOException {
        InputStream in = assetManager.open(from);
        OutputStream out = new FileOutputStream(new File(to));
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        out.flush();
        out.close();
    }


    // ------------------------------------------------------------------------
    // 3.  Standard Voice Data Check Activity
    // ------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context storageContext = EspeakApp.getStorageContext();
        ArrayList<String> availableLanguages = new ArrayList<>();
        ArrayList<String> unavailableLanguages = new ArrayList<>();

        // Auto-install if needed
        installVoiceDataIfMissing(storageContext);

        boolean haveBaseResources = hasBaseResources(storageContext);
        if (!haveBaseResources || canUpgradeResources(storageContext)) {
            if (!haveBaseResources) {
                unavailableLanguages.add(Locale.ENGLISH.toString());
            }
            returnResults(Engine.CHECK_VOICE_DATA_FAIL, availableLanguages, unavailableLanguages);
            return;
        }

        final SpeechSynthesis engine = new SpeechSynthesis(storageContext, mSynthReadyCallback);
        final List<Voice> voices = engine.getAvailableVoices();

        for (Voice voice : voices) {
            availableLanguages.add(voice.toString());
        }

        returnResults(Engine.CHECK_VOICE_DATA_PASS, availableLanguages, unavailableLanguages);
    }

    private void returnResults(int result, ArrayList<String> availableLanguages, ArrayList<String> unavailableLanguages) {
        final Intent returnData = new Intent();
        returnData.putStringArrayListExtra(Engine.EXTRA_AVAILABLE_VOICES, availableLanguages);
        returnData.putStringArrayListExtra(Engine.EXTRA_UNAVAILABLE_VOICES, unavailableLanguages);
        setResult(result, returnData);
        finish();
    }

    private final SynthReadyCallback mSynthReadyCallback = new SynthReadyCallback() {
        @Override
        public void onSynthDataReady(byte[] audioData) {
            // Do nothing.
        }

        @Override
        public void onSynthDataComplete() {
            // Do nothing.
        }
    };
}
