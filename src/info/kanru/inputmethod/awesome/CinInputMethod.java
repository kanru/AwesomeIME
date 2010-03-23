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

import android.inputmethodservice.Keyboard;
import android.os.Handler;
import android.os.Message;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.IOException;
import java.util.List;

public class CinInputMethod extends InputMethod {

    private StringBuilder mComposing = new StringBuilder();
    private WordComposer mWord = new WordComposer();
    private Suggest mSuggest;
    private CharSequence mBestWord;
    private CinDictionary mCinDictionary;

    private static final int MSG_UPDATE_SUGGESTIONS = 0;

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_SUGGESTIONS:
                updateSuggestions();
                break;
            }
        }
    };

    public CinInputMethod(AwesomeIME service) {
        super(service);

        mComposing.setLength(0);
    }

    public void pickSuggestionManually(int index, CharSequence suggestion) {
        pickSuggestion(suggestion);
        mComposing.setLength(0);
        mWord.reset();
    }

    private void pickSuggestion(CharSequence suggestion) {
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(suggestion, 1);
        }
        mService.setCommittedLength(suggestion.length());
        mService.setSuggestions(null, false, false, false);
    }

    private void handleBackspace() {
        boolean deleteChar = false;
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null)
            return;
        final int length = mComposing.length();
        if (length > 0) {
            mComposing.delete(length - 1, length);
            mWord.deleteLast();
            ic.setComposingText(mComposing, 1);
            postUpdateSuggestions();
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void postUpdateSuggestions() {
        mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UPDATE_SUGGESTIONS), 100);
    }

    private void updateSuggestions() {
        if (mHandler.hasMessages(MSG_UPDATE_SUGGESTIONS)) {
            mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
        }

        // Check if we have a suggestion engine attached.
        if (mSuggest == null)
            return;
        
        if (mComposing.length() == 0) {
            mService.setSuggestions(null, false, false, false);
            mBestWord = null;
            return;
        }

        List<CharSequence> stringList = mSuggest.getCinSuggestions(mService.getInputView(),
                                                                   mWord);
        CharSequence typedWord = mWord.getTypedWord();
        mService.setSuggestions(stringList, false, false, false); 
        if (stringList.size() > 0)
            mBestWord = stringList.get(0);
        else
            mBestWord = null;
        mService.setCandidatesViewShown(true);
    }

    private void handleCharacter(int primaryCode, int[] keyCodes) {
        if (primaryCode == ' ') {
            updateSuggestions();
            if (mComposing.length() == 0) {
                mService.sendSpace();
                return;
            }
            if (mBestWord != null)
                pickSuggestion(mBestWord);
            else
                pickSuggestion("");
            mComposing.setLength(0);
            mWord.reset();
            return;
        }
        mComposing.append((char) primaryCode);
        mWord.add(primaryCode, keyCodes);
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic != null) {
            ic.setComposingText(mComposing, 1);
        }
        postUpdateSuggestions();
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                handleBackspace();
                break;
            case Keyboard.KEYCODE_SHIFT:
                //handleShift();
                break;
            case Keyboard.KEYCODE_CANCEL:
                if (mService.isOptionsDialogShowing()) {
                    mService.handleClose();
                }
                break;
            case LatinKeyboardView.KEYCODE_OPTIONS:
                mService.showOptionsMenu();
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
                handleCharacter(primaryCode, keyCodes);
                break;
        }
    }

    @Override
    public void onText(CharSequence text) {
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null)
            return;
        ic.beginBatchEdit();
        ic.commitText(text, 1);
        ic.endBatchEdit();
    }

    @Override
    public void swipeDown() {
        mService.handleClose();
    }

    public void commitTyped(InputConnection inputConnection) {
        if (mComposing.length() > 0) {
            if (inputConnection != null) {
                inputConnection.commitText(mComposing, 1);
            }
            mService.setCommittedLength(mComposing.length());
            mComposing.setLength(0);
            mWord.reset();
            updateSuggestions();
        }
    }

    public void initSuggest() {
        mSuggest = new Suggest(mService);
        try {
            mCinDictionary = new CinDictionary("/sdcard/NewCJ3.tbl");
        } catch (IOException e) {}
        mSuggest.addDictionary(mCinDictionary);
    }

    public void close() {
        try {
            mCinDictionary.close();
        } catch (IOException e) {}
    }
}
