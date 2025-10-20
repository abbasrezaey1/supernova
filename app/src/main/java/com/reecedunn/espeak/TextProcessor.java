package com.reecedunn.espeak;

import android.util.Log;
import android.widget.TextView;
import org.json.JSONObject;

public class TextProcessor {
    private static final String TAG = "TextProcessor";

    private final TextView resultView;
    private String lastPrinted = "";
    private String lastWord = "";

    private boolean partialEnabled = true;
    private boolean finalEnabled = true;
    private boolean clipEnabled = true;

    private int minWords = 3;
    private int maxWords = 4;

    public TextProcessor(TextView resultView) {
        this.resultView = resultView;
    }

    public void setWordRange(int min, int max) {
        this.minWords = min;
        this.maxWords = max;
    }

    public boolean isClipEnabled() { return clipEnabled; }
    public void setClipEnabled(boolean enabled) { this.clipEnabled = enabled; }

    public boolean isPartialEnabled() { return partialEnabled; }
    public boolean isFinalEnabled() { return finalEnabled; }

    public void setPartialEnabled(boolean enabled) { this.partialEnabled = enabled; }
    public void setFinalEnabled(boolean enabled) { this.finalEnabled = enabled; }

    public void handlePartial(String hypothesis) {
        try {
            if (!partialEnabled) return;
            JSONObject obj = new JSONObject(hypothesis);
            String text = obj.optString("partial", "").trim();
            if (text.isEmpty()) return;

            String lastPart = getLastNWords(text);
            if (!isSameAsPrevious(lastPart)) {
                resultView.post(() -> resultView.setText(lastPart));
                lastPrinted = lastPart;
            }
        } catch (Exception e) {
            Log.e(TAG, "handlePartial error: " + e.getMessage());
        }
    }

    public void handleFinal(String hypothesis) {
        try {
            if (!finalEnabled) return;
            JSONObject obj = new JSONObject(hypothesis);
            String text = obj.optString("text", "").trim();
            if (text.isEmpty()) return;

            String lastPart = clipEnabled ? getLastNWords(text) : text;
            if (!isSameAsPrevious(lastPart)) {
                resultView.post(() -> resultView.setText(lastPart));
                lastPrinted = lastPart;
            }
        } catch (Exception e) {
            Log.e(TAG, "handleFinal error: " + e.getMessage());
        }
    }

    private String getLastNWords(String text) {
        if (text == null || text.isEmpty()) return "";
        int randomWordCount = (int) (Math.random() * (maxWords - minWords + 1)) + minWords;
        String[] words = text.trim().split("\\s+");
        int total = words.length;
        int start = Math.max(0, total - randomWordCount);

        StringBuilder builder = new StringBuilder();
        for (int i = start; i < total; i++) builder.append(words[i]).append(" ");
        return builder.toString().trim();
    }

    private boolean isSameAsPrevious(String word) {
        if (word == null || word.isEmpty()) return true;
        String normalized = word.toLowerCase().trim();
        if (normalized.equals(lastWord)) return true;
        lastWord = normalized;
        return false;
    }

    public String getLastPrinted() { return lastPrinted; }
    public void reset() { lastPrinted = ""; lastWord = ""; }
}
