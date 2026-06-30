package com.termux.app.terminal;

import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;

public class TermuxTerminalTextViewController implements TerminalExtraKeys.TerminalInput {

    public static final int KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD;
    public static final int KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MIN = 100;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MAX = 2000;

    private static final int MIN_COLUMNS = 2;
    private static final int MIN_ROWS = 2;

    private final TextView mTextView;

    private TermuxTerminalViewClient mClient;
    private TerminalSession mTermSession;
    private TerminalEmulator mEmulator;
    private int mCombiningAccent;
    private int mLastColumns;
    private int mLastRows;
    private final Handler mCursorBlinkerHandler = new Handler(Looper.getMainLooper());
    private Runnable mCursorBlinkerRunnable;
    private int mTerminalCursorBlinkerRate;
    private boolean mCursorVisible = true;

    private static boolean TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

    private static final String LOG_TAG = "TermuxTerminalTextViewController";

    public TermuxTerminalTextViewController(@NonNull TextView textView) {
        mTextView = textView;
        mTextView.setTextIsSelectable(true);
        mTextView.setInputType(InputType.TYPE_NULL);
        mTextView.setHorizontallyScrolling(false);
        mTextView.setSingleLine(false);
        mTextView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP)
                return mClient != null && mClient.onKeyUp(keyCode, event);
            return onKeyDown(keyCode, event);
        });
        mTextView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateSize());
    }

    public void setTerminalViewClient(TermuxTerminalViewClient client) {
        mClient = client;
    }

    public void setIsTerminalViewKeyLoggingEnabled(boolean value) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value;
    }

    public void setTextSize(int textSize) {
        mTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        updateSize();
        onScreenUpdated();
    }

    public boolean attachSession(TerminalSession session) {
        if (session == mTermSession) return false;
        mTermSession = session;
        mEmulator = null;
        mCombiningAccent = 0;
        updateSize();
        onScreenUpdated();
        return true;
    }

    public void updateSize() {
        if (mTermSession == null || mTextView.getWidth() <= 0 || mTextView.getHeight() <= 0) return;

        Paint paint = mTextView.getPaint();
        int contentWidth = Math.max(1, mTextView.getWidth() - mTextView.getPaddingLeft() - mTextView.getPaddingRight());
        int contentHeight = Math.max(1, mTextView.getHeight() - mTextView.getPaddingTop() - mTextView.getPaddingBottom());
        int cellWidth = Math.max(1, Math.round(paint.measureText("X")));
        Paint.FontMetricsInt fontMetrics = paint.getFontMetricsInt();
        int cellHeight = Math.max(1, fontMetrics.descent - fontMetrics.ascent);
        int columns = Math.max(MIN_COLUMNS, contentWidth / cellWidth);
        int rows = Math.max(MIN_ROWS, contentHeight / cellHeight);

        if (mEmulator != null && columns == mLastColumns && rows == mLastRows) return;

        boolean hadEmulator = mEmulator != null;
        mTermSession.updateSize(columns, rows, cellWidth, cellHeight);
        mLastColumns = columns;
        mLastRows = rows;
        mEmulator = mTermSession.getEmulator();
        if (!hadEmulator && mEmulator != null && mClient != null)
            mClient.onEmulatorSet();
        onScreenUpdated();
    }

    public void onScreenUpdated() {
        if (mEmulator == null) {
            mTextView.setText("");
            return;
        }

        mTextView.setText(getScreenTextWithCursor());
        mTextView.post(() -> {
            int scrollAmount = mTextView.getLayout() == null ? 0 :
                mTextView.getLayout().getLineTop(mTextView.getLineCount()) - mTextView.getHeight();
            mTextView.scrollTo(0, Math.max(scrollAmount, 0));
        });
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        return mTermSession;
    }

    @Nullable
    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public String getSelectedText() {
        int selectionStart = mTextView.getSelectionStart();
        int selectionEnd = mTextView.getSelectionEnd();
        if (selectionStart < 0 || selectionEnd < 0 || selectionStart == selectionEnd) return null;
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        return mTextView.getText().subSequence(start, end).toString();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED && mClient != null)
            mClient.logInfo(LOG_TAG, "onKeyDown(keyCode=" + keyCode + ", isSystem()=" + event.isSystem() + ", event=" + event + ")");
        if (mTermSession == null || mEmulator == null) return true;

        if (mClient != null && mClient.onKeyDown(keyCode, event, mTermSession)) {
            onScreenUpdated();
            return true;
        } else if (event.isSystem() && (mClient == null || !mClient.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return false;
        } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession.write(event.getCharacters());
            return true;
        }

        final int metaState = event.getMetaState();
        final boolean controlDown = event.isCtrlPressed() || (mClient != null && mClient.readControlKey());
        final boolean leftAltDown = (metaState & KeyEvent.META_ALT_LEFT_ON) != 0 || (mClient != null && mClient.readAltKey());
        final boolean shiftDown = event.isShiftPressed() || (mClient != null && mClient.readShiftKey());
        final boolean rightAltDownFromEvent = (metaState & KeyEvent.META_ALT_RIGHT_ON) != 0;

        int keyMod = 0;
        if (controlDown) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed() || leftAltDown) keyMod |= KeyHandler.KEYMOD_ALT;
        if (shiftDown) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (event.isNumLockOn()) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;
        if (!event.isFunctionPressed() && handleKeyCode(keyCode, keyMod)) return true;

        int bitsToClear = KeyEvent.META_CTRL_MASK;
        if (!rightAltDownFromEvent)
            bitsToClear |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        int effectiveMetaState = event.getMetaState() & ~bitsToClear;
        if (shiftDown) effectiveMetaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (mClient != null && mClient.readFnKey()) effectiveMetaState |= KeyEvent.META_FUNCTION_ON;

        int result = event.getUnicodeChar(effectiveMetaState);
        if (result == 0) return false;

        if ((result & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            if (mCombiningAccent != 0)
                inputCodePoint(event.getDeviceId(), mCombiningAccent, controlDown, leftAltDown);
            mCombiningAccent = result & KeyCharacterMap.COMBINING_ACCENT_MASK;
        } else {
            if (mCombiningAccent != 0) {
                int combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result);
                if (combinedChar > 0) result = combinedChar;
                mCombiningAccent = 0;
            }
            inputCodePoint(event.getDeviceId(), result, controlDown, leftAltDown);
        }

        return true;
    }

    public void inputCodePoint(int eventSource, int codePoint, boolean controlDownFromEvent, boolean leftAltDownFromEvent) {
        if (mTermSession == null) return;

        final boolean controlDown = controlDownFromEvent || (mClient != null && mClient.readControlKey());
        final boolean altDown = leftAltDownFromEvent || (mClient != null && mClient.readAltKey());

        if (mClient != null && mClient.onCodePoint(codePoint, controlDown, mTermSession)) return;

        if (controlDown) {
            if (codePoint >= 'a' && codePoint <= 'z') {
                codePoint = codePoint - 'a' + 1;
            } else if (codePoint >= 'A' && codePoint <= 'Z') {
                codePoint = codePoint - 'A' + 1;
            } else if (codePoint == ' ' || codePoint == '2') {
                codePoint = 0;
            } else if (codePoint == '[' || codePoint == '3') {
                codePoint = 27;
            } else if (codePoint == '\\' || codePoint == '4') {
                codePoint = 28;
            } else if (codePoint == ']' || codePoint == '5') {
                codePoint = 29;
            } else if (codePoint == '^' || codePoint == '6') {
                codePoint = 30;
            } else if (codePoint == '_' || codePoint == '7' || codePoint == '/') {
                codePoint = 31;
            } else if (codePoint == '8') {
                codePoint = 127;
            }
        }

        if (codePoint > -1) {
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                switch (codePoint) {
                    case 0x02DC:
                        codePoint = 0x007E;
                        break;
                    case 0x02CB:
                        codePoint = 0x0060;
                        break;
                    case 0x02C6:
                        codePoint = 0x005E;
                        break;
                    default:
                        break;
                }
            }
            mTermSession.writeCodePoint(altDown, codePoint);
        }
    }

    public boolean handleKeyCode(int keyCode, int keyMod) {
        if (mTermSession == null || mEmulator == null) return false;

        boolean shiftDown = (keyMod & KeyHandler.KEYMOD_SHIFT) != 0;
        if (shiftDown && (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            mTextView.scrollBy(0, keyCode == KeyEvent.KEYCODE_PAGE_UP ? -mTextView.getHeight() : mTextView.getHeight());
            return true;
        }

        String code = KeyHandler.getCode(keyCode, keyMod, mEmulator.isCursorKeysApplicationMode(), mEmulator.isKeypadApplicationMode());
        if (code == null) return false;
        mTermSession.write(code);
        return true;
    }

    public int[] getColumnAndRow(MotionEvent event) {
        Paint paint = mTextView.getPaint();
        int cellWidth = Math.max(1, Math.round(paint.measureText("X")));
        Paint.FontMetricsInt fontMetrics = paint.getFontMetricsInt();
        int cellHeight = Math.max(1, fontMetrics.descent - fontMetrics.ascent);
        int column = Math.max(0, (int) ((event.getX() - mTextView.getPaddingLeft()) / cellWidth));
        int row = Math.max(0, (int) ((event.getY() - mTextView.getPaddingTop()) / cellHeight));
        if (mEmulator != null) {
            column = Math.min(column, Math.max(0, mEmulator.mColumns - 1));
            row = Math.min(row, Math.max(0, mEmulator.mRows - 1));
        }
        return new int[]{column, row};
    }

    public boolean setTerminalCursorBlinkerRate(int blinkRate) {
        boolean valid = blinkRate == 0 || (blinkRate >= TERMINAL_CURSOR_BLINK_RATE_MIN && blinkRate <= TERMINAL_CURSOR_BLINK_RATE_MAX);
        mTerminalCursorBlinkerRate = valid ? blinkRate : 0;
        if (mTerminalCursorBlinkerRate == 0)
            stopTerminalCursorBlinker();
        return valid;
    }

    public void setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled) {
        stopTerminalCursorBlinker();
        if (mEmulator == null) return;

        mEmulator.setCursorBlinkingEnabled(false);
        mCursorVisible = true;
        mEmulator.setCursorBlinkState(true);

        if (startOnlyIfCursorEnabled && !mEmulator.isCursorEnabled()) {
            onScreenUpdated();
            return;
        }

        if (!start || mTerminalCursorBlinkerRate == 0) {
            onScreenUpdated();
            return;
        }

        mEmulator.setCursorBlinkingEnabled(true);
        mCursorBlinkerRunnable = new Runnable() {
            @Override
            public void run() {
                if (mEmulator == null) return;
                mCursorVisible = !mCursorVisible;
                mEmulator.setCursorBlinkState(mCursorVisible);
                onScreenUpdated();
                mCursorBlinkerHandler.postDelayed(this, mTerminalCursorBlinkerRate);
            }
        };
        onScreenUpdated();
        mCursorBlinkerHandler.postDelayed(mCursorBlinkerRunnable, mTerminalCursorBlinkerRate);
    }

    private void stopTerminalCursorBlinker() {
        if (mCursorBlinkerRunnable != null) {
            mCursorBlinkerHandler.removeCallbacks(mCursorBlinkerRunnable);
            mCursorBlinkerRunnable = null;
        }
    }

    private CharSequence getScreenTextWithCursor() {
        int activeTranscriptRows = mEmulator.getScreen().getActiveTranscriptRows();
        int firstRow = -activeTranscriptRows;
        int lastRow = mEmulator.mRows - 1;
        int cursorRow = mEmulator.getCursorRow();
        int cursorCol = mEmulator.getCursorCol();
        int cursorStart = -1;

        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int row = firstRow; row <= lastRow; row++) {
            String line = mEmulator.getScreen().getSelectedText(0, row, mEmulator.mColumns - 1, row, false);
            if (row == cursorRow && shouldDrawCursor()) {
                int lineStart = builder.length();
                builder.append(line);
                while (builder.length() - lineStart < cursorCol) {
                    builder.append(' ');
                }
                cursorStart = Math.min(lineStart + cursorCol, builder.length());
                if (cursorCol >= builder.length() - lineStart)
                    builder.append(' ');
            } else {
                builder.append(line);
            }

            if (row < lastRow)
                builder.append('\n');
        }

        if (cursorStart >= 0 && cursorStart < builder.length())
            applyCursorSpan(builder, cursorStart);

        return builder;
    }

    private boolean shouldDrawCursor() {
        return mEmulator.shouldCursorBeVisible();
    }

    private void applyCursorSpan(SpannableStringBuilder builder, int cursorStart) {
        int cursorEnd = Math.min(cursorStart + 1, builder.length());
        switch (mEmulator.getCursorStyle()) {
            case TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE:
                builder.setSpan(new UnderlineSpan(), cursorStart, cursorEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR:
                builder.insert(cursorStart, "|");
                builder.setSpan(new ForegroundColorSpan(mTextView.getCurrentTextColor()), cursorStart, cursorStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK:
            default:
                builder.setSpan(new BackgroundColorSpan(mTextView.getCurrentTextColor()), cursorStart, cursorEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new ForegroundColorSpan(getCursorForegroundColor()), cursorStart, cursorEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
        }
    }

    private int getCursorForegroundColor() {
        int color = mTextView.getCurrentTextColor();
        int red = 255 - Color.red(color);
        int green = 255 - Color.green(color);
        int blue = 255 - Color.blue(color);
        return Color.rgb(red, green, blue);
    }
}
