/*
 * Copyright (C) 2008-2009 Google Inc.
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

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.media.AudioManager;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.text.AutoText;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class AwesomeIME extends InputMethodService {
    static final boolean DEBUG = false;
    static final boolean TRACE = false;
    
    private static final String PREF_VIBRATE_ON = "vibrate_on";
    private static final String PREF_SOUND_ON = "sound_on";

    static final int KEYCODE_ENTER = '\n';
    static final int KEYCODE_SPACE = ' ';

    // Contextual menu positions
    private static final int POS_SETTINGS = 0;
    private static final int POS_METHOD = 1;
    
    private LatinKeyboardView mInputView;
    private CandidateViewContainer mCandidateViewContainer;
    private CandidateView mCandidateView;
    private CompletionInfo[] mCompletions;
    
    private AlertDialog mOptionsDialog;
    
    private KeyboardSwitcher mKeyboardSwitcher;
    
    private InputMethod mInputMethod;
    
    private int mCommittedLength;
    private boolean mCompletionOn;
    private boolean mAutoSpace;
    private boolean mCapsLock;
    private boolean mVibrateOn;
    private boolean mSoundOn;
    private int     mOrientation;

    private Vibrator mVibrator;
    private long mVibrateDuration;

    private AudioManager mAudioManager;
    // Align sound effect volume on music volume
    private final float FX_VOLUME = -1.0f;
    private boolean mSilentMode;

    @Override public void onCreate() {
        super.onCreate();
        //setStatusIcon(R.drawable.ime_qwerty);
        mKeyboardSwitcher = new KeyboardSwitcher(this);

        mInputMethod = new LatinInputMethod(this);
        mInputMethod.initSuggest();

        final Configuration conf = getResources().getConfiguration();
        mOrientation = conf.orientation;

        mVibrateDuration = getResources().getInteger(R.integer.vibrate_duration_ms);

        // register to receive ringer mode changes for silent mode
        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, filter);
    }

    @Override public void onDestroy() {
        mInputMethod.close();
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration conf) {
        // If orientation changed while predicting, commit the change
        if (conf.orientation != mOrientation) {
            mInputMethod.commitTyped(getCurrentInputConnection());
            mOrientation = conf.orientation;
        }
        if (mKeyboardSwitcher == null) {
            mKeyboardSwitcher = new KeyboardSwitcher(this);
        }
        mKeyboardSwitcher.makeKeyboards(true);
        super.onConfigurationChanged(conf);
    }

    @Override
    public View onCreateInputView() {
        mInputView = (LatinKeyboardView) getLayoutInflater().inflate(
                R.layout.input, null);
        mKeyboardSwitcher.setInputView(mInputView);
        mKeyboardSwitcher.makeKeyboards(true);
        mInputView.setOnKeyboardActionListener(mInputMethod);
        mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, 0);
        return mInputView;
    }

    @Override
    public View onCreateCandidatesView() {
        mKeyboardSwitcher.makeKeyboards(true);
        mCandidateViewContainer = (CandidateViewContainer) getLayoutInflater().inflate(
                R.layout.candidates, null);
        mCandidateViewContainer.initViews();
        mCandidateView = (CandidateView) mCandidateViewContainer.findViewById(R.id.candidates);
        mCandidateView.setService(this);
        setCandidatesViewShown(true);
        return mCandidateViewContainer;
    }

    @Override 
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        // In landscape mode, this method gets called without the input view being created.
        if (mInputView == null) {
            return;
        }

        mKeyboardSwitcher.makeKeyboards(false);
        mCapsLock = false;
        TextEntryState.newSession(this);

        boolean autoCorrectOn = false;
        boolean predictionOn = false;
        mCompletionOn = false;
        mCompletions = null;
        mAutoSpace = true;
        switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
            case EditorInfo.TYPE_CLASS_NUMBER:
            case EditorInfo.TYPE_CLASS_DATETIME:
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_SYMBOLS,
                                                  attribute.imeOptions);
                break;
            case EditorInfo.TYPE_CLASS_PHONE:
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_PHONE,
                                                  attribute.imeOptions);
                break;
            case EditorInfo.TYPE_CLASS_TEXT:
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                                                  attribute.imeOptions);
                predictionOn = true;
                int variation = attribute.inputType &  EditorInfo.TYPE_MASK_VARIATION;
                switch (variation) {
                case EditorInfo.TYPE_TEXT_VARIATION_PASSWORD:
                case EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                    // Make sure that passwords are not displayed in candidate view
                    predictionOn = false;
                    break;
                case EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME:
                    mAutoSpace = false;
                    break;
                case EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                    mAutoSpace = false;
                    predictionOn = false;
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_EMAIL,
                                                      attribute.imeOptions);
                    break;
                case EditorInfo.TYPE_TEXT_VARIATION_URI:
                    predictionOn = false;
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_URL,
                                                      attribute.imeOptions);
                    break;
                case EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE:
                    mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_IM,
                                                      attribute.imeOptions);
                    break;
                case EditorInfo.TYPE_TEXT_VARIATION_FILTER:
                    predictionOn = false;
                    break;
                case EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT:
                    // If it's a browser edit field and auto correct is not ON explicitly, then
                    // disable auto correction, but keep suggestions on.
                    if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0) {
                        autoCorrectOn = true;
                    }
                    break;
                default:
                    break;
                }
                // If NO_SUGGESTIONS is set, don't do prediction.
                if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
                    predictionOn = false;
                    autoCorrectOn = true;
                }
                // If it's not multiline and the autoCorrect flag is not set, then don't correct
                if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0 &&
                    (attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
                    autoCorrectOn = true;
                }
                if ((attribute.inputType & EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                    predictionOn = false;
                    mCompletionOn = isFullscreenMode();
                }
                mInputMethod.updateShiftKeyState(attribute);
                break;
            default:
                mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT,
                                                  attribute.imeOptions);
                mInputMethod.updateShiftKeyState(attribute);
        }
        mInputView.closing();
        setCandidatesViewShown(false);

        if (mCandidateView != null)
            mCandidateView.setSuggestions(null, false, false, false);

        loadSettings();
        mInputMethod.loadSettings();
        mInputMethod.setPredictionOn(predictionOn);
        mInputMethod.setAutoCorrectOn(autoCorrectOn);
        mInputView.setProximityCorrectionEnabled(true);

        if (TRACE)
            Debug.startMethodTracing("/data/trace/acinime");
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();

        if (mInputView != null) {
            mInputView.closing();
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        mInputMethod.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                                            candidatesStart, candidatesEnd);
    }

    @Override
    public void hideWindow() {
        if (TRACE)
            Debug.stopMethodTracing();
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
        TextEntryState.endSession();
    }

    @Override
    public void onDisplayCompletions(CompletionInfo[] completions) {
        if (false) {
            Log.i("foo", "Received completions:");
            for (int i=0; i<(completions != null ? completions.length : 0); i++) {
                Log.i("foo", "  #" + i + ": " + completions[i]);
            }
        }
        /*
        if (mCompletionOn) {
            mCompletions = completions;
            if (completions == null) {
                mCandidateView.setSuggestions(null, false, false, false);
                return;
            }
            
            List<CharSequence> stringList = new ArrayList<CharSequence>();
            for (int i=0; i<(completions != null ? completions.length : 0); i++) {
                CompletionInfo ci = completions[i];
                if (ci != null) stringList.add(ci.getText());
            }
            //CharSequence typedWord = mWord.getTypedWord();
            mCandidateView.setSuggestions(stringList, true, true, true);
            mBestWord = null;
            setCandidatesViewShown(isCandidateStripVisible() || mCompletionOn);
        }
        */
    }

    @Override
    public void setCandidatesViewShown(boolean shown) {
        // TODO: Remove this if we support candidates with hard keyboard
        if (onEvaluateInputViewShown()) {
            super.setCandidatesViewShown(shown);
        }
    }
    
    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0 && mInputView != null) {
                    if (mInputView.handleBack())
                        return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Enable shift key and DPAD to do selections
                if (mInputView != null && mInputView.isShown() && mInputView.isShifted()) {
                    event = new KeyEvent(event.getDownTime(), event.getEventTime(), 
                            event.getAction(), event.getKeyCode(), event.getRepeatCount(),
                            event.getDeviceId(), event.getScanCode(),
                            KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON);
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.sendKeyEvent(event);
                    return true;
                }
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    public boolean isAlphabetMode() {
        return mKeyboardSwitcher.isAlphabetMode();
    }

    public boolean isCinMode() {
        return mKeyboardSwitcher.getTextMode() == KeyboardSwitcher.MODE_TEXT_CIN;
    }

    public boolean addWordToDictionary(String word) {
        mInputMethod.addWord(word, 128);
        return true;
    }

    public LatinKeyboardView getInputView() {
        return mInputView;
    }

    public boolean isOptionsDialogShowing() {
        if (mOptionsDialog == null)
            return false;
        return mOptionsDialog.isShowing();
    }

    public boolean isCapsLock() {
        return mCapsLock;
    }

    public void setCapsLock(boolean b) {
        mCapsLock = b;
    }

    public void setAlphaMode() {
        mKeyboardSwitcher.setAlphaMode();
    }

    public void setCinMode() {
        mKeyboardSwitcher.setCinMode();
    }

    public boolean isSwitchKeyboardBack(int primaryCode) {
        return mKeyboardSwitcher.onKey(primaryCode);
    }

    public void setSuggestions(List<CharSequence> suggestions, boolean completions,
            boolean typedWordValid, boolean haveMinimalSuggestion) {
        mCandidateView.setSuggestions(suggestions, completions,
                                      typedWordValid, haveMinimalSuggestion);
    }

    public void setShifted(boolean set) {
        getInputView().setShifted(set);
    }

    public boolean isShifted() {
        return getInputView().isShifted();
    }

    public Keyboard getKeyboard() {
        return getInputView().getKeyboard();
    }

    public void toggleShift() {
        mKeyboardSwitcher.toggleShift();
    }

    public void handleClose() {
        mInputMethod.commitTyped(getCurrentInputConnection());
        requestHideSelf(0);
        getInputView().closing();
        TextEntryState.endSession();
    }

    public boolean getCompletionOn() {
        return mCompletionOn;
    }

    public CompletionInfo[] getCompletions() {
        return mCompletions;
    }

    public int getCommittedLength() {
        return mCommittedLength;
    }

    public void setCommittedLength(int len) {
        mCommittedLength = len;
    }

    public void clearCandidateView() {
        if (mCandidateView != null) {
            mCandidateView.clear();
        }
    }

    public boolean isAutoSpace() {
        return mAutoSpace;
    }

    public void pickSuggestionManually(int index, CharSequence suggestion) {
        if (getCompletionOn() && getCompletions() != null && index >= 0 &&
            index < getCompletions().length) {
            CompletionInfo ci = getCompletions()[index];
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitCompletion(ci);
            }
            setCommittedLength(suggestion.length());
            clearCandidateView();
            mInputMethod.updateShiftKeyState(getCurrentInputEditorInfo());
            return;
        }
        mInputMethod.pickSuggestionManually(index, suggestion);
    }

    public void sendSpace() {
        sendKeyChar((char)KEYCODE_SPACE);
        mInputMethod.updateShiftKeyState(getCurrentInputEditorInfo());
        //onKey(KEY_SPACE[0], KEY_SPACE);
    }

    // receive ringer mode changes to detect silent mode
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRingerMode();
        }
    };

    // update flags for silent mode
    private void updateRingerMode() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (mAudioManager != null) {
            mSilentMode = (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);
        }
    }

    public void playKeyClick(int primaryCode) {
        // if mAudioManager is null, we don't have the ringer state yet
        // mAudioManager will be set by updateRingerMode
        if (mAudioManager == null) {
            if (getInputView() != null) {
                updateRingerMode();
            }
        }
        if (mSoundOn && !mSilentMode) {
            // FIXME: Volume and enable should come from UI settings
            // FIXME: These should be triggered after auto-repeat logic
            int sound = AudioManager.FX_KEYPRESS_STANDARD;
            switch (primaryCode) {
                case Keyboard.KEYCODE_DELETE:
                    sound = AudioManager.FX_KEYPRESS_DELETE;
                    break;
                case KEYCODE_ENTER:
                    sound = AudioManager.FX_KEYPRESS_RETURN;
                    break;
                case KEYCODE_SPACE:
                    sound = AudioManager.FX_KEYPRESS_SPACEBAR;
                    break;
            }
            mAudioManager.playSoundEffect(sound, FX_VOLUME);
        }
    }

    public void vibrate() {
        if (!mVibrateOn) {
            return;
        }
        if (mVibrator == null) {
            mVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        mVibrator.vibrate(mVibrateDuration);
    }

    private void launchSettings() {
        handleClose();
        Intent intent = new Intent();
        intent.setClass(AwesomeIME.this, AwesomeIMESettings.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void loadSettings() {
        // Get the settings preferences
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, false);
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, false);
    }

    public void showOptionsMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setIcon(R.drawable.ic_dialog_keyboard);
        builder.setNegativeButton(android.R.string.cancel, null);
        CharSequence itemSettings = getString(R.string.english_ime_settings);
        CharSequence itemInputMethod = getString(R.string.inputMethod);
        builder.setItems(new CharSequence[] {
                itemSettings, itemInputMethod},
                new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface di, int position) {
                di.dismiss();
                switch (position) {
                    case POS_SETTINGS:
                        launchSettings();
                        break;
                    case POS_METHOD:
                        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                            .showInputMethodPicker();
                        break;
                }
            }
        });
        builder.setTitle(getResources().getString(R.string.english_ime_name));
        mOptionsDialog = builder.create();
        Window window = mOptionsDialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = getInputView().getWindowToken();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog.show();
    }

    public void changeKeyboardMode() {
        mKeyboardSwitcher.toggleSymbols();
        if (isCapsLock() && isAlphabetMode()) {
            ((LatinKeyboard) getInputView().getKeyboard()).setShiftLocked(mCapsLock);
        }

        mInputMethod.updateShiftKeyState(getCurrentInputEditorInfo());
    }
    
    @Override protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        super.dump(fd, fout, args);
        
        final Printer p = new PrintWriterPrinter(fout);
        /*
        p.println("AwesomeIME state :");
        p.println("  Keyboard mode = " + mKeyboardSwitcher.getKeyboardMode());
        p.println("  mCapsLock=" + mCapsLock);
        p.println("  mComposing=" + getComposing().toString());
        p.println("  predictionOn=" + predictionOn);
        p.println("  mCorrectionMode=" + mCorrectionMode);
        p.println("  mPredicting=" + isPredicting());
        p.println("  mAutoCorrectOn=" + mAutoCorrectOn);
        p.println("  mAutoSpace=" + mAutoSpace);
        p.println("  mCompletionOn=" + mCompletionOn);
        p.println("  TextEntryState.state=" + TextEntryState.getState());
        p.println("  mSoundOn=" + mSoundOn);
        p.println("  mVibrateOn=" + mVibrateOn);
        */
    }
}
