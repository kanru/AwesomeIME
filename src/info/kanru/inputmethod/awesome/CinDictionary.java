package info.kanru.inputmethod.awesome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import android.util.Log;

public class CinDictionary extends Dictionary {

    private static final String TAG = "CinDictionary";

    private static final int mHeaderSize = 4;
    private static final int mKeySize = 5;
    private static final int mPosSize = 4;
    private static final int mItemSize = mKeySize + mPosSize;

    // Header
    private long mNItems;

    // Dict info
    private long mDictOffset;

    private RandomAccessFile fd;

    public CinDictionary(String filepath) throws IOException {
        loadDictionary(filepath);
    }

    private void loadDictionary(String filepath) throws IOException {
        fd = new RandomAccessFile(filepath, "r");
    }

    private long readInt() throws IOException {
        int b1, b2, b3, b4;
        b1 = fd.read();
        b2 = fd.read();
        b3 = fd.read();
        b4 = fd.read();
        return (((long)b1 << 24) +
                ((long)b2 << 16) +
                ((long)b3 << 8) +
                (long)b4);
    }

    private long seekStart(String q) throws IOException {
        long l, r, m = 0;
        byte[] buf = new byte[mKeySize];

        fd.seek(0);
        mNItems = readInt();
        mDictOffset = mHeaderSize + mItemSize * mNItems;

        l = -1;
        r = mNItems;
        while (l+1!=r) {
            m = (r-l)/2+l;
            fd.seek(m*mItemSize+mHeaderSize);
            fd.readFully(buf);
            String key = new String(buf);
            if (key.compareTo(q) < 0)
                l = m;
            else
                r = m;
        }
        return r * mItemSize + mHeaderSize;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int b;
        while (true) {
            b = fd.read();
            if (b == '\n') {
                String out = bo.toString("UTF-8");
                bo.close();
                return out;
            } else {
                bo.write(b);
            }
        }
    }

    @Override public void getWords(final WordComposer codes, final WordCallback callback) {
        try {
            CharSequence query = codes.getTypedWord();
            String q = query.toString().toLowerCase();
            long cur = seekStart(q);

            byte[] buf = new byte[mKeySize];
            int nph = 0;
            long pos = 0;
            fd.seek(cur);
            //System.out.println("seek: " + cur);
            while (true) {
                fd.readFully(buf);
                if (pos == 0) {
                    pos = readInt();
                } else {
                    fd.skipBytes(4);
                }
                String key = new String(buf);
                //System.out.println(key);
                if (key.startsWith(q)) {
                    nph += 1;
                    if (nph > 100) {
                        break;
                    }
                } else {
                    break;
                }
            }
            fd.seek(mDictOffset + pos);
            //System.out.println("seek: " + pos);
            for (int i = 0; i < nph; i++) {
                String v = readLine();
                callback.addWord(v.toCharArray(), 0, v.length(), nph-i);
            }
        } catch (IOException e) {
            Log.e("CinDictionary", "Failed to query " + codes.getTypedWord());
            e.printStackTrace();
        }
    }

    @Override public boolean isValidWord(CharSequence word) {
        return false;
    }

    public synchronized void close() throws IOException {
        fd.close();
    }

    @Override protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
}
