/*************************************************************************/
/*                                                                       */
/*                  Language Technologies Institute                      */
/*                     Carnegie Mellon University                        */
/*                         Copyright (c) 2010                            */
/*                        All Rights Reserved.                           */
/*                                                                       */
/*  Permission is hereby granted, free of charge, to use and distribute  */
/*  this software and its documentation without restriction, including   */
/*  without limitation the rights to use, copy, modify, merge, publish,  */
/*  distribute, sublicense, and/or sell copies of this work, and to      */
/*  permit persons to whom this work is furnished to do so, subject to   */
/*  the following conditions:                                            */
/*   1. The code must retain the above copyright notice, this list of    */
/*      conditions and the following disclaimer.                         */
/*   2. Any modifications must be clearly marked as such.                */
/*   3. Original authors' names are not deleted.                         */
/*   4. The authors' names are not used to endorse or promote products   */
/*      derived from this software without specific prior written        */
/*      permission.                                                      */
/*                                                                       */
/*  CARNEGIE MELLON UNIVERSITY AND THE CONTRIBUTORS TO THIS WORK         */
/*  DISCLAIM ALL WARRANTIES WITH REGARD TO THIS SOFTWARE, INCLUDING      */
/*  ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS, IN NO EVENT   */
/*  SHALL CARNEGIE MELLON UNIVERSITY NOR THE CONTRIBUTORS BE LIABLE      */
/*  FOR ANY SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES    */
/*  WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN   */
/*  AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,          */
/*  ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF       */
/*  THIS SOFTWARE.                                                       */
/*                                                                       */
/*************************************************************************/
/*             Author:  Alok Parlikar (aup@cs.cmu.edu)                   */
/*               Date:  July 2012                                        */
/*************************************************************************/

package edu.cmu.cs.speech.tts.flite;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

public class Voice {
	private final static String LOG_TAG = "Flite_Java_" + Voice.class.getSimpleName();
	private final static String LEGACY_FLITE_DATA_PATH = Environment.getExternalStorageDirectory()
			+ "/flite-data/";
	private final static String VOICE_BASE_URL = "http://festvox.org/flite/voices/cg/voxdata-v2.0.0/";

	// Resolved at runtime by init(Context). The flite-data tree must live in
	// storage that is readable before the first user unlock (device-protected
	// storage on API 24+), otherwise TalkBack cannot speak on the lock screen.
	private static String sFliteDataPath;

	private String mVoiceName;
	private String mVoiceMD5;
	private String mVoiceLanguage;
	private String mVoiceCountry;
	private String mVoiceVariant;
	private boolean mIsValidVoice;
	private String mVoicePath;
	private boolean mIsVoiceAvailable;

	/**
	 * Resolve the flite-data directory for the running device. Must be called
	 * from every entry point (service, activity, provider) before any code
	 * touches getDataStorageBasePath().
	 *
	 * On API 24+ we use device-protected storage so the engine works during
	 * Direct Boot (lock screen, before first unlock). On older releases we
	 * keep the legacy external-storage path so existing voice downloads stay
	 * usable without a re-download.
	 */
	public static synchronized void init(Context context) {
		if (sFliteDataPath != null) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			sFliteDataPath = resolveDeviceProtectedPath(context);
			maybeMigrateLegacyVoices();
		} else {
			sFliteDataPath = LEGACY_FLITE_DATA_PATH;
		}
		Log.i(LOG_TAG, "flite-data path resolved to: " + sFliteDataPath);
	}

	@TargetApi(Build.VERSION_CODES.N)
	private static String resolveDeviceProtectedPath(Context context) {
		Context de = context.createDeviceProtectedStorageContext();
		return new File(de.getFilesDir(), "flite-data").getAbsolutePath() + "/";
	}

	/**
	 * @return absolute path to the flite-data directory
	 */
	public static String getDataStorageBasePath() {
		if (sFliteDataPath == null) {
			// Fall back to the legacy path so callers that forgot to init
			// don't NPE. They should still call init() — this preserves
			// behaviour on pre-N devices but won't survive Direct Boot.
			return LEGACY_FLITE_DATA_PATH;
		}
		return sFliteDataPath;
	}

	private static void maybeMigrateLegacyVoices() {
		File legacy = new File(LEGACY_FLITE_DATA_PATH);
		File target = new File(sFliteDataPath);
		if (!legacy.isDirectory()) {
			return;
		}
		if (new File(target, "cg").isDirectory()) {
			// Already migrated (or freshly installed on N+).
			return;
		}
		Log.i(LOG_TAG, "Migrating voices from " + legacy + " to " + target);
		try {
			copyRecursively(legacy, target);
		} catch (IOException e) {
			// Best-effort: user can re-download from the voice manager UI.
			Log.w(LOG_TAG, "Voice migration failed: " + e.getMessage());
		}
	}

	private static void copyRecursively(File src, File dst) throws IOException {
		if (src.isDirectory()) {
			if (!dst.exists() && !dst.mkdirs()) {
				throw new IOException("Could not create " + dst);
			}
			String[] children = src.list();
			if (children == null) return;
			for (String name : children) {
				copyRecursively(new File(src, name), new File(dst, name));
			}
			return;
		}
		FileInputStream in = new FileInputStream(src);
		try {
			FileOutputStream out = new FileOutputStream(dst);
			try {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
				}
			} finally {
				out.close();
			}
		} finally {
			in.close();
		}
	}

	/**
	 * @return base URL to download voices and other flite data
	 */
	public static String getDownloadURLBasePath() {
		return VOICE_BASE_URL;
	}

	/**
	 * @param voiceInfoLine is the line that is found in "voices.list" file
	 * as downloaded on the server and cached. This line has text in the format:
	 * language-country-variant<TAB>MD5SUM
	 */
	Voice(String voiceInfoLine) {
		boolean parseSuccessful = false;

		String[] voiceInfo = voiceInfoLine.split("\t");
		if (voiceInfo.length != 2) {
			Log.e(LOG_TAG, "Voice line could not be read: " + voiceInfoLine);
		}
		else {
			mVoiceName = voiceInfo[0];
			mVoiceMD5 = voiceInfo[1];

			String[] voiceParams = mVoiceName.split("-");
			if(voiceParams.length != 3) {
				Log.e(LOG_TAG,"Incorrect voicename:" + mVoiceName);
			}
			else {
				mVoiceLanguage = voiceParams[0];
				mVoiceCountry = voiceParams[1];
				mVoiceVariant = voiceParams[2];
				parseSuccessful = true;
			}
		}

		if (parseSuccessful) {
			mIsValidVoice = true;
			mVoicePath = getDataStorageBasePath() + "cg/" + mVoiceLanguage +
					"/" + mVoiceCountry + "/" + mVoiceVariant + ".cg.flitevox";
			checkVoiceAvailability();
		}
		else {
			mIsValidVoice = false;
		}

	}

	private void checkVoiceAvailability() {
		Log.v(LOG_TAG, "Checking for Voice Available: " + mVoiceName);

		mIsVoiceAvailable = false;

		// The file should exist, as well as the MD5 sum should match.
		// Only then do we mark a voice as available.
		//
		// We can attempt getting an MD5sum, and an IOException will
		// tell us if the file didn't exist at all.

		MessageDigest md;
		try {
			md = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			Log.e(LOG_TAG, "MD5 could not be computed");
			return;
		}

		FileInputStream fis;
		try {
			fis = new FileInputStream(mVoicePath);
		}
		catch (FileNotFoundException e) {
			Log.e(LOG_TAG, "Voice File not found: " + mVoicePath);
			return;
		}

		byte[] dataBytes = new byte[1024];
		int nread = 0;
		try {
			while ((nread = fis.read(dataBytes)) != -1) {
				md.update(dataBytes, 0, nread);
			}
		} catch (IOException e) {
			Log.e(LOG_TAG, "Could not read voice file: " + mVoicePath);
			return;
		}
		finally {
			try {
				fis.close();
			} catch (IOException e) {
				// Ignoring this exception.
			}
		}

		byte[] mdbytes = md.digest();

		StringBuffer sb = new StringBuffer();
        for (int i = 0; i < mdbytes.length; i++) {
          sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
        }

		if (sb.toString().equals(mVoiceMD5)) {
			mIsVoiceAvailable = true;
			return;
		}
		else {
			Log.e(LOG_TAG,"Voice file found, but MD5 sum incorrect. Found" +
					sb.toString() + ". Expected: " + mVoiceMD5);
			return;
		}
	}

	public boolean isValid() {
		return mIsValidVoice;
	}

	public boolean isAvailable() {
		return mIsVoiceAvailable;
	}

	public String getName() {
		return mVoiceName;
	}

	public String getDisplayName() {
		Locale loc = new Locale(mVoiceLanguage, mVoiceCountry, mVoiceVariant);
		String displayName = loc.getDisplayLanguage() +
				"(" + loc.getDisplayCountry() + "," + loc.getVariant() + ")";
		return displayName;
	}

	public String getVariant() {
		return mVoiceVariant;
	}

	public String getDisplayLanguage() {
		Locale loc = new Locale(mVoiceLanguage, mVoiceCountry, mVoiceVariant);
		String displayLanguage = loc.getDisplayLanguage() +
				" (" + loc.getDisplayCountry() + ")";

		return displayLanguage;
	}

	public String getPath() {
		return mVoicePath;
	}

	public Locale getLocale() {
		return new Locale(mVoiceLanguage, mVoiceCountry, mVoiceVariant);
	}
}
