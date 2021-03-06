//
// Copyright (c) 2016 Couchbase, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
// except in compliance with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
// either express or implied. See the License for the specific language governing permissions
// and limitations under the License.
//
package com.couchbase.lite.support;

import com.couchbase.lite.Database;
import com.couchbase.lite.Manager;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.Utils;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Created by hideki on 6/17/15.
 */
public class RevisionUtils {

    public static Map<String, Object> makeRevisionHistoryDict(List<RevisionInternal> history) {
        if (history == null) {
            return null;
        }

        // Try to extract descending numeric prefixes:
        List<String> suffixes = new ArrayList<String>();
        int start = -1;
        int lastRevNo = -1;
        for (RevisionInternal rev : history) {
            int revNo = parseRevIDNumber(rev.getRevID());
            String suffix = parseRevIDSuffix(rev.getRevID());
            if (revNo > 0 && suffix.length() > 0) {
                if (start < 0) {
                    start = revNo;
                } else if (revNo != lastRevNo - 1) {
                    start = -1;
                    break;
                }
                lastRevNo = revNo;
                suffixes.add(suffix);
            } else {
                start = -1;
                break;
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        if (start == -1) {
            // we failed to build sequence, just stuff all the revs in list
            suffixes = new ArrayList<String>();
            for (RevisionInternal rev : history) {
                suffixes.add(rev.getRevID());
            }
        } else {
            result.put("start", start);
        }
        result.put("ids", suffixes);

        return result;
    }

    /**
     * Splits a revision ID into its generation number and opaque suffix string
     */
    public static int parseRevIDNumber(String rev) {
        int result = -1;
        int dashPos = rev.indexOf('-');
        if (dashPos >= 0) {
            try {
                result = Integer.parseInt(rev.substring(0, dashPos));
            } catch (NumberFormatException e) {
                // ignore, let it return -1
            }
        }
        return result;
    }

    /**
     * Splits a revision ID into its generation number and opaque suffix string
     */
    public static String parseRevIDSuffix(String rev) {
        String result = null;
        int dashPos = rev.indexOf('-');
        if (dashPos >= 0) {
            result = rev.substring(dashPos + 1);
        }
        return result;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public static byte[] asCanonicalJSON(RevisionInternal rev) {
        return asCanonicalJSON(rev.getProperties());
    }

    static List<String> specialKeysToRemove = Arrays.asList(
            "_id",
            "_rev",
            "_deleted",
            "_revisions",
            "_revs_info",
            "_conflicts",
            "_deleted_conflicts",
            "_local_seq");

    static List<String> specialKeysToLeave = Arrays.asList(
            "_attachments",
            "_removed");

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public static byte[] asCanonicalJSON(Map<String, Object> props) {
        if (props == null) {
            return null;
        }

        Map<String, Object> properties = new HashMap<String, Object>(props.size());
        for (String key : props.keySet()) {
            boolean shouldAdd = false;
            if (!key.startsWith("_")) {
                shouldAdd = true;
            } else if (specialKeysToRemove.contains(key)) {
                shouldAdd = false;
            } else if (specialKeysToLeave.contains(key)) {
                shouldAdd = true;
            } else {
                Log.e(Database.TAG, "CBLDatabase: Invalid top-level key '%s' in document to be inserted", key);
                return null;
            }
            if (shouldAdd) {
                properties.put(key, props.get(key));
            }
        }

        byte[] json = null;
        try {
            // https://fasterxml.github.io/jackson-databind/javadoc/2.5/com/fasterxml/jackson/databind/SerializationFeature.html#ORDER_MAP_ENTRIES_BY_KEYS
            // public static final SerializationFeature ORDER_MAP_ENTRIES_BY_KEYS
            // Feature that determines whether Map entries are first sorted by key before
            // serialization or not: if enabled, additional sorting step is performed if necessary
            // (not necessary for SortedMaps), if disabled, no additional sorting is needed.
            // Feature is disabled by default.
            json = Manager.getObjectMapper()
                    .writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(properties);
        } catch (Exception e) {
            Log.e(Database.TAG, "Error serializing " + properties + " to JSON", e);
        }
        return json;
    }

    /**
     * in CBLDatabase+Insertion.m
     * - (NSString*) generateRevID: (CBL_Revision*)rev
     * withJSON: (NSData*)json
     * attachments: (NSDictionary*)attachments
     * prevID: (NSString*) prevID
     *
     * @exclude
     */
    @InterfaceAudience.Private
    public static String generateRevID(byte[] json, boolean deleted, String prevID) {
        MessageDigest md5Digest;

        // Revision IDs have a generation count, a hyphen, and a UUID.

        int generation = 0;
        if (prevID != null) {
            generation = RevisionInternal.generationFromRevID(prevID);
            if (generation == 0) {
                return null;
            }
        }

        // Generate a getDigest for this revision based on the previous revision ID, document JSON,
        // and attachment digests. This doesn't need to be secure; we just need to ensure that this
        // code consistently generates the same ID given equivalent revisions.
        try {
            md5Digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // single byte - length of previous revision id
        // +
        // previous revision id
        int length = 0;
        byte[] prevIDUTF8 = null;
        if (prevID != null) {
            prevIDUTF8 = prevID.getBytes(Charset.forName("UTF-8"));
            length = prevIDUTF8.length;
        }
        if (length > 0xFF) {
            return null;
        }
        byte lengthByte = (byte) (length & 0xFF);
        byte[] lengthBytes = new byte[]{lengthByte};
        md5Digest.update(lengthBytes); // prefix with length byte
        if (length > 0 && prevIDUTF8 != null) {
            md5Digest.update(prevIDUTF8);
        }

        // single byte - deletion flag
        int isDeleted = ((deleted != false) ? 1 : 0);
        byte[] deletedByte = new byte[]{(byte) isDeleted};
        md5Digest.update(deletedByte);

        // json
        if (json != null) {
            md5Digest.update(json);
        }
        byte[] md5DigestResult = md5Digest.digest();

        String digestAsHex = Utils.bytesToHex(md5DigestResult);

        int generationIncremented = generation + 1;
        return String.format(Locale.ENGLISH, "%d-%s", generationIncremented, digestAsHex).toLowerCase();
    }
}
