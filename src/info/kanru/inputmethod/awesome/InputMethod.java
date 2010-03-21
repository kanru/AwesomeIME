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

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;


public abstract class InputMethod
    implements KeyboardView.OnKeyboardActionListener {

    protected AwesomeIME mService;

    protected boolean mPredictionOn;

    public InputMethod(AwesomeIME service) {
        mService = service;
    }
    
    public void setPredictionOn(boolean on) {
        mPredictionOn = on;
    }

    public boolean isPredictionOn() {
        boolean predictionOn = mPredictionOn;
        if (mService.isCinMode())
            predictionOn = true;
        //if (isFullscreenMode()) predictionOn &= mPredictionLandscape;
        return predictionOn;
    }

    public void setAutoCorrectOn(boolean on) {}

    public void commitTyped(InputConnection inputConnection) {}

    public void updateShiftKeyState(EditorInfo attr) {}

    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {}

    public void pickSuggestionManually(int index, CharSequence suggestion) {}

    public void addWord(String word, int freq) {}

    public void loadSettings() {}

    public void initSuggest() {}

    public void close() {}

    /* start implement OnKeyboardActionListener */

    public void onKey(int primaryCode, int[] keyCodes) {
    }

    public void onPress(int primaryCode) {
        mService.vibrate();
        mService.playKeyClick(primaryCode);
    }

    public void onRelease(int primaryCode) {}

    public void onText(CharSequence text) {}

    public void swipeDown() {}

    public void swipeUp() {}

    public void swipeRight() {}

    public void swipeLeft() {}

    /* end OnKeyboardActionListener */
}
