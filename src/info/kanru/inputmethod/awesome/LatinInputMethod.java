/*
 * Copyright (C) 2010 Kan-Ru Chen
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package info.kanru.inputmethod.awesome;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.AutoText;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.IOException;
import java.util.List;

public class LatinInputMethod extends InputMethod {

    private static final String PREF_AUTO_CAP = "auto_cap";
    private static final String PREF_QUICK_FIXES = "quick_fixes";
    private static final String PREF_SHOW_SUGGESTIONS = "show_suggestions";
    private static final String PREF_AUTO_COMPLETE = "auto_complete";

    // How many continuous deletes at which to start deleting at a higher speed.
    private static final int DELETE_ACCELERATE_AT = 20;
    // Key events coming any faster than this are long-presses.
    private static final int QUICK_PRESS = 200;
    // A word that is frequently typed and get's promoted to the user dictionary, uses this
    // frequency.
    static final int FREQUENCY_FOR_AUTO_ADD = 250;
    // Weight added to a user typing a new word that doesn't get corrected (or is reverted)
    static final int FREQUENCY_FOR_TYPED = 1;
    // Weight added to a user picking a new word from the suggestion strip
    static final int FREQUENCY_FOR_PICKED = 3;

    private static final int MSG_UPDATE_SUGGESTIONS = 0;
    private static final int MSG_UPDATE_SHIFT_STATE = 1;
    
    static final int KEYCODE_ENTER = '\n';

    private boolean mAutoCap;
    private boolean mAutoCorrectOn;
    private int     mCorrectionMode;
    private boolean mQuickFixes;
    private boolean mShowSuggestions;
    private boolean mPredictionOn;
    private CharSequence mBestWord;

    // Indicates whether the suggestion strip is to be on in landscape
    private boolean mJustAccepted;
    
    private int mDeleteCount;
    private long mLastKeyTime;

    private boolean mPredicting;

    private UserDictionary mUserDictionary;
    private ContactsDictionary mContactsDictionary;
    private ExpandableDictionary mAutoDictionary;

    private StringBuilder mComposing = new StringBuilder();
    private WordComposer mWord = new WordComposer();
    private Suggest mSuggest;

    private String mWordSeparators;
    private String mSentenceSeparators;

    private CharSequence mJustRevertedSeparator;
    
    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_SUGGESTIONS:
                    updateSuggestions();
                    break;
                case MSG_UPDATE_SHIFT_STATE:
                    updateShiftKeyState(mService.getCurrentInputEditorInfo());
                    break;
            }
        }
    };

    public LatinInputMethod(AwesomeIME service) {
        super(service);

        mDeleteCount = 0;
        mLastKeyTime = 0;
        mPredicting = false;
        mComposing.setLength(0);
    }

    private WordComposer getWord() {
        return mWord;
    }

    private boolean isPredicting() {
        return mPredicting;
    }

    private void setPredicting(boolean b) {
        mPredicting = b;
    }

    private void autoAddWord(String word, int freq) {
        mAutoDictionary.addWord(word, freq);
    }

    private void checkDeleteCount(int primaryCode) {
        long when = SystemClock.uptimeMillis();
        if (primaryCode != Keyboard.KEYCODE_DELETE || 
                when > mLastKeyTime + QUICK_PRESS) {
            mDeleteCount = 0;
        }
        mLastKeyTime = when;
    }

    private int getDeleteCount() {
        return mDeleteCount;
    }

    private void incDeleteCount() {
        mDeleteCount++;
    }

    private Suggest getSuggest() {
        return mSuggest;
    }

    private boolean isAutoCorrectOn() {
        return mAutoCorrectOn;
    }

    private int getCorrectionMode () {
        return mCorrectionMode;
    }

    private boolean isAlphabet(int code) {
        if (Character.isLetter(code)) {
            return true;
        } else {
            return false;
        }
    }

    private boolean isCandidateStripVisible() {
        return isPredictionOn() && mShowSuggestions;
    }

    private boolean isValidWord(CharSequence suggestion) {
        return mAutoDictionary.isValidWord(suggestion);
    }

    private void promoteToUserDictionary(String word, int frequency) {
        if (mUserDictionary.isValidWord(word)) return;
        mUserDictionary.addWord(word, frequency);
    }

    private boolean preferCapitalization() {
        return getWord().isCapitalized();
    }

    private StringBuilder getComposing() {
        return mComposing;
    }

    private void setJustRevertedSeparator(CharSequence sep) {
        mJustRevertedSeparator = sep;
    }

    private CharSequence getJustRevertedSeparator() {
        return mJustRevertedSeparator;
    }

    private void setJustAccepted(boolean b) {
        mJustAccepted = b;
    }

    private boolean getJustAccepted() {
        return mJustAccepted;
    }

    private void pickDefaultSuggestion() {
        // Complete any pending candidate query first
        updateSuggestions();
        //Log.i("AcinIME", "mBestWord: " + mBestWord);
        if (getBestWord() != null) {
            TextEntryState.acceptedDefault(getWord().getTypedWord(),
                                           getBestWord());
            setJustAccepted(true);
            pickSuggestion(getBestWord());
        }
    }

    private CharSequence getBestWord() {
        return mBestWord;
    }

    private void setBestWord(CharSequence bw) {
        mBestWord = bw;
    }

    private boolean hasMessages(int m) {
        return mHandler.hasMessages(m);
    }

    private void removeMessages(int m) {
        mHandler.removeMessages(m);
    }

    private void updateSuggestions() {
        if (hasMessages(MSG_UPDATE_SUGGESTIONS)) {
            removeMessages(MSG_UPDATE_SUGGESTIONS);
        }

        // Check if we have a suggestion engine attached.
        if (getSuggest() == null || !isPredictionOn()) {
            return;
        }
        
        if (!isPredicting()) {
            mService.setSuggestions(null, false, false, false);
            return;
        }

        List<CharSequence> stringList = getSuggest().getSuggestions(mService.getInputView(),
                                                                    getWord(), false);
        boolean correctionAvailable = getSuggest().hasMinimalCorrection();
        //|| mCorrectionMode == mSuggest.CORRECTION_FULL;
        CharSequence typedWord = getWord().getTypedWord();
        // If we're in basic correct
        boolean typedWordValid = getSuggest().isValidWord(typedWord);
        if (getCorrectionMode() == Suggest.CORRECTION_FULL) {
            correctionAvailable |= typedWordValid;
        }
        // Don't auto-correct words with multiple capital letter
        correctionAvailable &= !getWord().isMostlyCaps();

        mService.setSuggestions(stringList, false, typedWordValid, correctionAvailable); 
        if (stringList.size() > 0) {
            if (correctionAvailable && !typedWordValid && stringList.size() > 1) {
                setBestWord(stringList.get(1));
            } else {
                setBestWord(typedWord);
            }
        } else {
            setBestWord(null);
        }
        mService.setCandidatesViewShown(isCandidateStripVisible() ||
                                        mService.getCompletionOn());
    }

    private void postUpdateShiftKeyState() {
        mHandler.removeMessages(MSG_UPDATE_SHIFT_STATE);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UPDATE_SHIFT_STATE), 300);
    }

    private void postUpdateSuggestions() {
        mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UPDATE_SUGGESTIONS), 100);
    }

    public void pickSuggestionManually(int index, CharSequence suggestion) {
        pickSuggestion(suggestion);
        TextEntryState.acceptedSuggestion(getComposing().toString(),
                                          suggestion);
        // Follow it with a space
        if (mService.isAutoSpace()) {
            mService.sendSpace();
            // Fool the state watcher so that a subsequent backspace will not do a revert
            TextEntryState.typedCharacter((char) AwesomeIME.KEYCODE_SPACE, true);
        }
    }

    private void pickSuggestion(CharSequence suggestion) {
        if (mService.isCapsLock()) {
            suggestion = suggestion.toString().toUpperCase();
        } else if (preferCapitalization() 
                || (mService.isAlphabetMode() && mService.isShifted())) {
            suggestion = suggestion.toString().toUpperCase().charAt(0)
                    + suggestion.subSequence(1, suggestion.length()).toString();
        }
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(suggestion, 1);
        }
        // Add the word to the auto dictionary if it's not a known word
        if (isValidWord(suggestion) ||
            !getSuggest().isValidWord(suggestion)) {
            autoAddWord(suggestion.toString(), FREQUENCY_FOR_PICKED);
        }
        setPredicting(false);
        mService.setCommittedLength(suggestion.length());
        mService.setSuggestions(null, false, false, false);
        updateShiftKeyState(mService.getCurrentInputEditorInfo());
    }

    private void handleBackspace() {
        boolean deleteChar = false;
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) return;
        if (isPredicting()) {
            StringBuilder composing = getComposing();
            final int length = composing.length();
            if (length > 0) {
                composing.delete(length - 1, length);
                getWord().deleteLast();
                ic.setComposingText(composing, 1);
                if (composing.length() == 0) {
                    setPredicting(false);
                }
                postUpdateSuggestions();
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        } else {
            deleteChar = true;
        }
        postUpdateShiftKeyState();
        TextEntryState.backspace();
        if (TextEntryState.getState() == TextEntryState.STATE_UNDO_COMMIT) {
            revertLastWord(deleteChar);
            return;
        } else if (deleteChar) {
            mService.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            if (getDeleteCount() > DELETE_ACCELERATE_AT) {
                mService.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            }
        }
        setJustRevertedSeparator(null);
    }

    private void handleShift() {
        Keyboard currentKeyboard = mService.getKeyboard();
        if (mService.isAlphabetMode()) {
            // Alphabet keyboard
            checkToggleCapsLock();
            mService.setShifted(mService.isCapsLock() || !mService.isShifted());
        } else {
            mService.toggleShift();
        }
    }

    private void checkToggleCapsLock() {
        if (mService.getInputView().getKeyboard().isShifted()) {
            toggleCapsLock();
        }
    }

    private void toggleCapsLock() {
        mService.setCapsLock(!mService.isCapsLock());
        if (mService.isAlphabetMode()) {
            //getInputView().setShifted(mCapsLock);
            ((LatinKeyboard) mService
             .getInputView()
             .getKeyboard())
                .setShiftLocked(mService.isCapsLock());
        }
    }

    private void handleSeparator(int primaryCode) {
        boolean pickedDefault = false;
        // Handle separator
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic != null) {
            ic.beginBatchEdit();
        }
        if (isPredicting()) {
            // In certain languages where single quote is a separator, it's better
            // not to auto correct, but accept the typed word. For instance, 
            // in Italian dov' should not be expanded to dove' because the elision
            // requires the last vowel to be removed.
            if (isAutoCorrectOn() && primaryCode != '\'' && 
                (getJustRevertedSeparator() == null 
                 || getJustRevertedSeparator().length() == 0 
                 || getJustRevertedSeparator().charAt(0) != primaryCode)) {
                pickDefaultSuggestion();
                pickedDefault = true;
            } else {
                commitTyped(ic);
            }
        }
        mService.sendKeyChar((char)primaryCode);
        TextEntryState.typedCharacter((char) primaryCode, true);
        if (TextEntryState.getState() == TextEntryState.STATE_PUNCTUATION_AFTER_ACCEPTED 
            && primaryCode != KEYCODE_ENTER) {
            swapPunctuationAndSpace();
        } else if (isPredictionOn() && primaryCode == ' ') { 
            //else if (TextEntryState.STATE_SPACE_AFTER_ACCEPTED) {
            doubleSpace();
        }
        if (pickedDefault && getBestWord() != null) {
            TextEntryState.acceptedDefault(getWord().getTypedWord(),
                                           getBestWord());
        }
        updateShiftKeyState(mService.getCurrentInputEditorInfo());
        if (ic != null) {
            ic.endBatchEdit();
        }
    }

    private void swapPunctuationAndSpace() {
        final InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) return;
        CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
        if (lastTwo != null && lastTwo.length() == 2
            && lastTwo.charAt(0) == mService.KEYCODE_SPACE &&
            isSentenceSeparator(lastTwo.charAt(1))) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(lastTwo.charAt(1) + " ", 1);
            ic.endBatchEdit();
            updateShiftKeyState(mService.getCurrentInputEditorInfo());
        }
    }
    
    private void doubleSpace() {
        //if (!mAutoPunctuate) return;
        if (getCorrectionMode() == Suggest.CORRECTION_NONE)
            return;
        final InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) return;
        CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && Character.isLetterOrDigit(lastThree.charAt(0))
            && lastThree.charAt(1) == mService.KEYCODE_SPACE &&
            lastThree.charAt(2) == mService.KEYCODE_SPACE) {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(2, 0);
            ic.commitText(". ", 1);
            ic.endBatchEdit();
            updateShiftKeyState(mService.getCurrentInputEditorInfo());
        }
    }
    

    private void handleCharacter(int primaryCode, int[] keyCodes) {
        if (isAlphabet(primaryCode) && isPredictionOn() &&
            !isCursorTouchingWord()) {
            if (!isPredicting()) {
                setPredicting(true);
                getComposing().setLength(0);
                getWord().reset();
            }
        }
        if (mService.isShifted()) {
            // TODO: This doesn't work with ß, need to fix it in the next release.
            if (keyCodes == null || keyCodes[0] < Character.MIN_CODE_POINT
                    || keyCodes[0] > Character.MAX_CODE_POINT) {
                return;
            }
            primaryCode = new String(keyCodes, 0, 1).toUpperCase().charAt(0);
        }
        if (isPredicting()) {
            if (mService.isShifted() && getComposing().length() == 0) {
                getWord().setCapitalized(true);
            }
            getComposing().append((char) primaryCode);
            getWord().add(primaryCode, keyCodes);
            InputConnection ic = mService.getCurrentInputConnection();
            if (ic != null) {
                ic.setComposingText(getComposing(), 1);
            }
            postUpdateSuggestions();
        } else {
            mService.sendKeyChar((char)primaryCode);
        }
        updateShiftKeyState(mService.getCurrentInputEditorInfo());
        TextEntryState.typedCharacter((char) primaryCode,
                                      isWordSeparator(primaryCode));
    }

    public void commitTyped(InputConnection inputConnection) {
        if (isPredicting()) {
            setPredicting(false);
            if (getComposing().length() > 0) {
                if (inputConnection != null) {
                    inputConnection.commitText(mComposing, 1);
                }
                mService.setCommittedLength(getComposing().length());
                TextEntryState.acceptedTyped(mComposing);
                autoAddWord(getComposing().toString(), FREQUENCY_FOR_TYPED);
            }
            updateSuggestions();
        }
    }

    public void updateShiftKeyState(EditorInfo attr) {
        InputConnection ic = mService.getCurrentInputConnection();
        if (attr != null && mService.getInputView() != null && mService.isAlphabetMode()
                && ic != null) {
            int caps = 0;
            EditorInfo ei = mService.getCurrentInputEditorInfo();
            if (mAutoCap && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
                caps = ic.getCursorCapsMode(attr.inputType);
            }
            mService.getInputView().setShifted(mService.isCapsLock() || caps != 0);
        }
    }

    private void revertLastWord(boolean deleteChar) {
        final int length = getComposing().length();
        if (!isPredicting() && length > 0) {
            final InputConnection ic = mService.getCurrentInputConnection();
            setPredicting(true);
            ic.beginBatchEdit();
            setJustRevertedSeparator(ic.getTextBeforeCursor(1, 0));
            if (deleteChar)
                ic.deleteSurroundingText(1, 0);
            int toDelete = mService.getCommittedLength();
            CharSequence toTheLeft = ic.getTextBeforeCursor(mService.getCommittedLength(),
                                                            0);
            if (toTheLeft != null && toTheLeft.length() > 0 
                && isWordSeparator(toTheLeft.charAt(0))) {
                toDelete--;
            }
            ic.deleteSurroundingText(toDelete, 0);
            ic.setComposingText(getComposing(), 1);
            TextEntryState.backspace();
            ic.endBatchEdit();
            postUpdateSuggestions();
        } else {
            mService.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            setJustRevertedSeparator(null);
        }
    }

    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        // If the current selection in the text view changes, we should
        // clear whatever candidate text we have.
        if (getComposing().length() > 0 && isPredicting() && (newSelStart != candidatesEnd
                || newSelEnd != candidatesEnd)) {
            getComposing().setLength(0);
            setPredicting(false);
            updateSuggestions();
            TextEntryState.reset();
            InputConnection ic = mService.getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        } else if (!isPredicting() && !getJustAccepted()
                && TextEntryState.getState() == TextEntryState.STATE_ACCEPTED_DEFAULT) {
            TextEntryState.reset();
        }
        setJustAccepted(false);
        postUpdateShiftKeyState();
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        checkDeleteCount(primaryCode);
        switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                handleBackspace();
                incDeleteCount();
                break;
            case Keyboard.KEYCODE_SHIFT:
                handleShift();
                break;
            case Keyboard.KEYCODE_CANCEL:
                if (mService.isOptionsDialogShowing()) {
                    mService.handleClose();
                }
                break;
            case LatinKeyboardView.KEYCODE_OPTIONS:
                mService.showOptionsMenu();
                break;
            case LatinKeyboardView.KEYCODE_SHIFT_LONGPRESS:
                if (mService.isCapsLock()) {
                    handleShift();
                } else {
                    toggleCapsLock();
                }
                break;
            case LatinKeyboardView.KEYCODE_ALPHA_MODE:
                mService.setAlphaMode();
                break;
            case LatinKeyboardView.KEYCODE_CIN_MODE:
                mService.setCinMode();
                break;
            case Keyboard.KEYCODE_MODE_CHANGE:
                mService.changeKeyboardMode();
                break;
            default:
                if (isWordSeparator(primaryCode)) {
                    handleSeparator(primaryCode);
                } else {
                    handleCharacter(primaryCode, keyCodes);
                }
                // Cancel the just reverted state
                setJustRevertedSeparator(null);
        }
        if (mService.isSwitchKeyboardBack(primaryCode)) {
            mService.changeKeyboardMode();
        }
    }

    @Override
    public void onText(CharSequence text) {
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) return;
        ic.beginBatchEdit();
        if (isPredicting()) {
            commitTyped(ic);
        }
        ic.commitText(text, 1);
        ic.endBatchEdit();
        updateShiftKeyState(mService.getCurrentInputEditorInfo());
        setJustRevertedSeparator(null);
    }

    @Override
    public void swipeRight() {
        if (LatinKeyboardView.DEBUG_AUTO_PLAY) {
            ClipboardManager cm = ((ClipboardManager)mService
                                   .getSystemService(mService.CLIPBOARD_SERVICE));
            CharSequence text = cm.getText();
            if (!TextUtils.isEmpty(text)) {
                mService.getInputView().startPlaying(text.toString());
            }
        }
    }

    @Override
    public void swipeDown() {
        mService.handleClose();
    }

    public void loadSettings() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mService);
        mAutoCap = sp.getBoolean(PREF_AUTO_CAP, true);
        mQuickFixes = sp.getBoolean(PREF_QUICK_FIXES, true);
        // If there is no auto text data, then quickfix is forced to "on", so that the other options
        // will continue to work
        if (AutoText.getSize(mService.getInputView()) < 1) mQuickFixes = true;
        mShowSuggestions = sp.getBoolean(PREF_SHOW_SUGGESTIONS, true) & mQuickFixes;
        boolean autoComplete = sp.getBoolean(PREF_AUTO_COMPLETE,
                mService.getResources().getBoolean(R.bool.enable_autocorrect)) & mShowSuggestions;
        mAutoCorrectOn = mSuggest != null && (autoComplete || mQuickFixes);
        mCorrectionMode = autoComplete
                ? Suggest.CORRECTION_FULL
                : (mQuickFixes ? Suggest.CORRECTION_BASIC : Suggest.CORRECTION_NONE);
    }

    public void initSuggest() {
        mSuggest = new Suggest(mService, R.raw.main);
        mSuggest.setCorrectionMode(mCorrectionMode);
        mUserDictionary = new UserDictionary(mService);
        //mContactsDictionary = new ContactsDictionary(this);
        mAutoDictionary = new AutoDictionary(mService);
        mSuggest.setUserDictionary(mUserDictionary);
        //mSuggest.setContactsDictionary(mContactsDictionary);
        mSuggest.setAutoDictionary(mAutoDictionary);
        mWordSeparators = mService.getResources().getString(R.string.word_separators);
        mSentenceSeparators = mService.getResources().getString(R.string.sentence_separators);
    }

    private boolean isCursorTouchingWord() {
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence toLeft = ic.getTextBeforeCursor(1, 0);
        CharSequence toRight = ic.getTextAfterCursor(1, 0);
        if (!TextUtils.isEmpty(toLeft)
                && !isWordSeparator(toLeft.charAt(0))) {
            return true;
        }
        if (!TextUtils.isEmpty(toRight) 
                && !isWordSeparator(toRight.charAt(0))) {
            return true;
        }
        return false;
    }
    
    protected String getWordSeparators() {
        return mWordSeparators;
    }
    
    private boolean isWordSeparator(int code) {
        String separators = getWordSeparators();
        return separators.contains(String.valueOf((char)code));
    }

    private boolean isSentenceSeparator(int code) {
        return mSentenceSeparators.contains(String.valueOf((char)code));
    }

    public void close() {
        mUserDictionary.close();
        //mContactsDictionary.close();
    }

    public void setAutoCorrectOn(boolean autoCorrectOn) {
        if (!autoCorrectOn) {
            mAutoCorrectOn = false;
            if (mCorrectionMode == Suggest.CORRECTION_FULL) {
                mCorrectionMode = Suggest.CORRECTION_BASIC;
            }
        }
        if (mSuggest != null) {
            mSuggest.setCorrectionMode(mCorrectionMode);
        }
        mPredictionOn = mPredictionOn && mCorrectionMode > 0;
    }

    public void addWord(String word, int freq) {
        mUserDictionary.addWord(word, freq);
    }

    class AutoDictionary extends ExpandableDictionary {
        // If the user touches a typed word 2 times or more, it will become valid.
        private static final int VALIDITY_THRESHOLD = 2 * FREQUENCY_FOR_PICKED;
        // If the user touches a typed word 5 times or more, it will be added to the user dict.
        private static final int PROMOTION_THRESHOLD = 5 * FREQUENCY_FOR_PICKED;

        public AutoDictionary(Context context) {
            super(context);
        }

        @Override
        public boolean isValidWord(CharSequence word) {
            final int frequency = getWordFrequency(word);
            return frequency > VALIDITY_THRESHOLD;
        }

        @Override
        public void addWord(String word, int addFrequency) {
            final int length = word.length();
            // Don't add very short or very long words.
            if (length < 2 || length > getMaxWordLength()) return;
            super.addWord(word, addFrequency);
            final int freq = getWordFrequency(word);
            if (freq > PROMOTION_THRESHOLD) {
                LatinInputMethod.this.promoteToUserDictionary(word, FREQUENCY_FOR_AUTO_ADD);
            }
        }
    }
}
