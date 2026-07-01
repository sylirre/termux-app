package com.termux.app.terminal;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.method.KeyListener;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.annotation.Nullable;

public class TerminalEditText extends EditText {

    public static final int TERMINAL_INPUT_TYPE = InputType.TYPE_CLASS_TEXT |
        InputType.TYPE_TEXT_FLAG_MULTI_LINE |
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

    private static final KeyListener TERMINAL_KEY_LISTENER = new KeyListener() {
        @Override
        public int getInputType() {
            return TERMINAL_INPUT_TYPE;
        }

        @Override
        public boolean onKeyDown(View view, Editable text, int keyCode, KeyEvent event) {
            return !event.isSystem();
        }

        @Override
        public boolean onKeyUp(View view, Editable text, int keyCode, KeyEvent event) {
            return !event.isSystem();
        }

        @Override
        public boolean onKeyOther(View view, Editable text, KeyEvent event) {
            return true;
        }

        @Override
        public void clearMetaKeyState(View view, Editable content, int states) {
        }
    };

    private TerminalInputController mTerminalInputController;
    private boolean mSuppressTerminalScreenUpdateAccessibilityEvents;

    public TerminalEditText(Context context) {
        super(context);
    }

    public TerminalEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TerminalEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setTerminalInputController(@Nullable TerminalInputController terminalInputController) {
        mTerminalInputController = terminalInputController;
    }

    public void setTerminalKeyListener() {
        setKeyListener(TERMINAL_KEY_LISTENER);
    }

    public void setSuppressTerminalScreenUpdateAccessibilityEvents(boolean suppress) {
        mSuppressTerminalScreenUpdateAccessibilityEvents = suppress;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(EditText.class.getName());
        info.setEditable(true);
        info.setMultiLine(true);
        info.setPassword(false);
        info.setInputType(TERMINAL_INPUT_TYPE);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && mTerminalInputController != null) {
            CharSequence text = arguments == null ? null :
                arguments.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
            if (text != null)
                mTerminalInputController.sendTextFromInputConnection(text);
            return true;
        }

        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        if (shouldSuppressTerminalScreenUpdateAccessibilityEvent(event))
            return;

        super.sendAccessibilityEventUnchecked(event);
    }

    private boolean shouldSuppressTerminalScreenUpdateAccessibilityEvent(AccessibilityEvent event) {
        if (!mSuppressTerminalScreenUpdateAccessibilityEvents || event == null)
            return false;

        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return true;
        }

        return eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            (event.getContentChangeTypes() & AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) != 0;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        TerminalInputController controller = mTerminalInputController;
        if (controller == null)
            return super.onCreateInputConnection(outAttrs);

        if (controller.isTerminalViewSelected()) {
            if (controller.shouldEnforceCharBasedInput()) {
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            } else {
                outAttrs.inputType = InputType.TYPE_NULL;
            }
        } else {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;

        return new BaseInputConnection(this, true) {
            private final Editable mEditable = Editable.Factory.getInstance().newEditable("");

            @Override
            public Editable getEditable() {
                return mEditable;
            }

            @Override
            public boolean finishComposingText() {
                controller.logTerminalInput("IME: finishComposingText()");
                super.finishComposingText();
                sendEditableToTerminal();
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                controller.logTerminalInput("IME: commitText(\"" + text + "\", " + newCursorPosition + ")");
                super.commitText(text, newCursorPosition);
                sendEditableToTerminal();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                controller.logTerminalInput("IME: deleteSurroundingText(" + leftLength + ", " + rightLength + ")");
                KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < leftLength; i++)
                    controller.sendKeyEventFromInputConnection(deleteKey);
                return super.deleteSurroundingText(leftLength, rightLength);
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                controller.logTerminalInput("IME: deleteSurroundingTextInCodePoints(" + beforeLength + ", " + afterLength + ")");
                return deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                controller.logTerminalInput("IME: sendKeyEvent(" + event + ")");
                return controller.sendKeyEventFromInputConnection(event);
            }

            @Override
            public boolean performEditorAction(int editorAction) {
                controller.logTerminalInput("IME: performEditorAction(" + editorAction + ")");
                controller.sendTextFromInputConnection("\n");
                return true;
            }

            private void sendEditableToTerminal() {
                if (mEditable.length() == 0) return;
                controller.sendTextFromInputConnection(mEditable);
                mEditable.clear();
            }
        };
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.cut || id == android.R.id.paste || id == android.R.id.pasteAsPlainText)
            return true;
        return super.onTextContextMenuItem(id);
    }

    @Override
    public boolean isSuggestionsEnabled() {
        return false;
    }

    public interface TerminalInputController {
        boolean isTerminalViewSelected();

        boolean shouldEnforceCharBasedInput();

        void logTerminalInput(String message);

        boolean sendKeyEventFromInputConnection(KeyEvent event);

        void sendTextFromInputConnection(CharSequence text);
    }
}
