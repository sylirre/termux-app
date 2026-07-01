package com.termux.app.terminal;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.annotation.Nullable;

public class TerminalEditText extends EditText {

    private TerminalInputController mTerminalInputController;

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

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
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
