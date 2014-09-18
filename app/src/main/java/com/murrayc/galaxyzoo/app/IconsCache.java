package com.murrayc.galaxyzoo.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.murrayc.galaxyzoo.app.provider.Config;
import com.murrayc.galaxyzoo.app.provider.HttpUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

class IconsCache {
    //TODO: Generate these automatically, making sure they are unique:
    private static final String CACHE_FILE_WORKFLOW_ICONS = "workflowicons";
    private static final String CACHE_FILE_EXAMPLE_ICONS = "exampleicons";
    private static final String CACHE_FILE_CSS = "css";
    private static final String PREF_KEY_AUTH_ICONS_CACHE_LAST_MOD = "icons-cache-last-mod";

    private final DecisionTree mDecisionTree;
    private final File mCacheDir;

    //TODO: Don't put both kinds of icons in the same map:
    //See this about the use of the LruCache:
    //http://developer.android.com/training/displaying-bitmaps/cache-bitmap.html#memory-cache
    private final LruCache<String, Bitmap> mIcons = new LruCache<>(30);
    private Bitmap mBmapWorkflowIcons = null;
    private Bitmap mBmapExampleIcons = null;

    /**
     * This does network IO so it should not be used in the UI's main thread.
     * For instance, do this in an AsyncTask, as in Singleton.init().
     *
     * @param context
     * @param decisionTree
     */
    public IconsCache(final Context context, final DecisionTree decisionTree) {
        this.mDecisionTree = decisionTree;
        
        mCacheDir = context.getExternalCacheDir();

        long lastModified = 0;

        boolean loadFromNetwork = false;
        if (Utils.getNetworkIsConnected(context)) {
            //Check if the files on the server have changed since we last cached them:
            final String[] uris = {Config.ICONS_URI, Config.EXAMPLES_URI, Config.ICONS_CSS_URI};
            lastModified = HttpUtils.getLatestLastModified(uris);
            final SharedPreferences prefs = Utils.getPreferences(context);

            final long prevLastModified = prefs.getLong(PREF_KEY_AUTH_ICONS_CACHE_LAST_MOD, 0);
            if ((lastModified == 0) /* Always update if we can't get the last-modified from the server */
                    || (lastModified > prevLastModified)) {
                loadFromNetwork = true;
            }
        }

        if (loadFromNetwork) {
            //Get the updated files from the server and re-process them:
            readIconsFileSync(Config.ICONS_URI, CACHE_FILE_WORKFLOW_ICONS);
            readIconsFileSync(Config.EXAMPLES_URI, CACHE_FILE_EXAMPLE_ICONS);
            readCssFileSync(Config.ICONS_CSS_URI, CACHE_FILE_CSS);

            //Remember the dates of the files from the server,
            //so we can check again next time.
            final SharedPreferences prefs = Utils.getPreferences(context);
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(PREF_KEY_AUTH_ICONS_CACHE_LAST_MOD, lastModified);
            editor.apply();
        } else {
            //Just get the cached icons:
            reloadCachedIcons();
        }

        mBmapWorkflowIcons = null;
        mBmapExampleIcons = null;
    }

    private void reloadCachedIcons() {
        mIcons.evictAll();
        final DecisionTree.Question question = mDecisionTree.getFirstQuestion();
        reloadIconsForQuestion(question);
    }

    private void reloadIconsForQuestion(final DecisionTree.Question question) {
        for (final DecisionTree.Answer answer : question.answers) {
            //Get the icon for the answer:
            reloadIcon(answer.getIcon());

            reloadExampleImages(question, answer);

            //Recurse:
            final DecisionTree.Question nextQuestion = mDecisionTree.getNextQuestionForAnswer(question.getId(), answer.getId());
            if (nextQuestion != null) {
                reloadIconsForQuestion(nextQuestion);
            }
        }

        for (final DecisionTree.Checkbox checkbox : question.checkboxes) {
            reloadIcon(checkbox.getIcon());

            reloadExampleImages(question, checkbox);
        }
    }

    private void reloadExampleImages(final DecisionTree.Question question, final DecisionTree.BaseButton answer) {
        //Get the example images for the answer or checkbox:
        for (int i = 0; i < answer.getExamplesCount(); i++) {
            final String exampleIconName = answer.getExampleIconName(question.getId(), i);
            reloadIcon(exampleIconName);
        }
    }

    private void reloadIcon(final String cssName) {
        //Avoid loading and adding it again:
        if(mIcons.get(cssName) != null) {
            return;
        }

        final String cacheFileUri = getCacheIconFileUri(cssName);
        final Bitmap bitmap = BitmapFactory.decodeFile(cacheFileUri);
        mIcons.put(cssName, bitmap);
    }

    private void readIconsFileSync(final String uriStr, final String cacheId) {
        final String cacheFileUri = getCacheFileUri(cacheId);
        HttpUtils.cacheUriToFileSync(uriStr, cacheFileUri);
    }

    private void readCssFileSync(final String uriStr, final String cacheId) {
        final String cacheFileUri = getCacheFileUri(cacheId);
        HttpUtils.cacheUriToFileSync(uriStr, cacheFileUri);
        onCssDownloaded();
    }

    private void cacheIconsForQuestion(final DecisionTree.Question question, final String css) {
        final String cacheFileCss = getCacheFileUri(CACHE_FILE_CSS); //Avoid repeated calls to this.

        if (mBmapWorkflowIcons == null) {
            final String cacheFileIcons = getCacheFileUri(CACHE_FILE_WORKFLOW_ICONS);
            mBmapWorkflowIcons = BitmapFactory.decodeFile(cacheFileIcons);
        }

        if (mBmapExampleIcons == null) {
            final String cacheFileIcons = getCacheFileUri(CACHE_FILE_EXAMPLE_ICONS);
            mBmapExampleIcons = BitmapFactory.decodeFile(cacheFileIcons);
        }


        for (final DecisionTree.Answer answer : question.answers) {
            //Get the icon for the answer:
            final String iconName = answer.getIcon();
            getIconPositionFromCss(mBmapWorkflowIcons, css, iconName, false);
            getExampleImages(question, css, answer);


            //Recurse:
            final DecisionTree.Question nextQuestion = mDecisionTree.getNextQuestionForAnswer(question.getId(), answer.getId());
            if (nextQuestion != null) {
                cacheIconsForQuestion(nextQuestion, css);
            }
        }

        for (final DecisionTree.Checkbox checkbox : question.checkboxes) {
            final String iconName = checkbox.getIcon();
            getIconPositionFromCss(mBmapWorkflowIcons, css, iconName, false);
            getExampleImages(question, css, checkbox);
        }
    }


    private void getExampleImages(DecisionTree.Question question, String css, DecisionTree.BaseButton answer) {
        //Get the example images for the answer or checkbox:
        for (int i = 0; i < answer.getExamplesCount(); i++) {
            final String exampleIconName = answer.getExampleIconName(question.getId(), i);
            getIconPositionFromCss(mBmapExampleIcons, css, exampleIconName, true);
        }
    }

    private void onCssDownloaded() {
        final String cacheFileUri = getCacheFileUri(CACHE_FILE_CSS);
        final String css = getFileContents(cacheFileUri);

        // Recurse through the questions, looking at each icon:
        mIcons.evictAll();
        final DecisionTree.Question question = mDecisionTree.getFirstQuestion();
        cacheIconsForQuestion(question, css);
    }

    private String getCacheIconFileUri(final String cssName) {
        return getCacheFileUri("icon_" + cssName);
    }

    private String getCacheFileUri(final String cacheId) {
        final File file = new File(mCacheDir.getPath(), cacheId);
        try {
            file.createNewFile();
        } catch (IOException e) {
            Log.error("Could not create cache file.", e);
            return null;
        }

        return file.getAbsolutePath();
    }

    String getFileContents(final String fileUri) {
        File file = new File(fileUri);
        try (
            final FileInputStream fis = new FileInputStream(file);
            final InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
            final BufferedReader bufferedReader = new BufferedReader(isr);
        ) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            return sb.toString();
        } catch (FileNotFoundException e) {
            Log.error("readFile failed.", e);
            return "";
        } catch (UnsupportedEncodingException e) {
            Log.error("readFile failed.", e);
            return "";
        } catch (IOException e) {
            Log.error("readFile failed.", e);
            return "";
        }
    }


    // This little helper function is instead of using a whole CSS parser,
    // in the absence of an easy choice of CSS parser.
    // http://sourceforge.net/projects/cssparser/ doesn't seem to be usable on Android because
    // Android's org.w3c.dom doesn't have the css package, with classes such as CSSStyleSheet.
    void getIconPositionFromCss(final Bitmap icons, final String css, final String cssName, boolean isExampleIcon) {
        if (mIcons.get(cssName) != null) {
            //Avoid getting it again.
            return;
        }

        Pattern p = null;
        try {
            String prefix;
            if (isExampleIcon) {
                prefix = "\\.example-thumbnail\\.";
            } else {
                prefix = "a\\.workflow-";
            }

            p = Pattern.compile(prefix + cssName + "\\{background-position:(-?[0-9]+)(px)? (-?[0-9]+)(px)?\\}");
            //p = Pattern.compile("a.workflow-" + cssName);
        } catch (PatternSyntaxException e) {
            Log.error("Regex error", e);
            return;
        }

        final Matcher m = p.matcher(css);
        while (m.find()) {
            if (m.groupCount() < 4) {
                Log.info("Regex error. Unexpected groups count:" + m.groupCount());
            } else {
                final String xStr = m.group(1);
                final String yStr = m.group(3);

                final int x = -(Integer.parseInt(xStr)); //Change negative (CSS) to positive (Bitmap).
                final int y = -(Integer.parseInt(yStr)); //Change negative (CSS) to positive (Bitmap).

                //TODO: Avoid hard-coding the 100px, 100px here:
                //We catch the IllegalArgumentException to avoid letting the CSS crash our app
                //just by having a wrong value.
                try {
                    final Bitmap bmapIcon = Bitmap.createBitmap(icons, x, y, 100, 100);

                    //Cache the file locally so we don't need to get it over the network next time:
                    //TODO: Use the cache from the volley library?
                    //See http://blog.wittchen.biz.pl/asynchronous-loading-and-caching-bitmaps-with-volley/
                    final String cacheFileUri = getCacheIconFileUri(cssName);
                    cacheBitmapToFile(bmapIcon, cacheFileUri);

                    mIcons.put(cssName, bmapIcon);
                } catch (final IllegalArgumentException ex) {
                    Log.error("IllegalArgumentException from createBitmap() for iconName=" + cssName + ", x=" + x + ", y=" + y + ", icons.width=" + icons.getWidth() + ", icons.height=" + icons.getHeight());
                }
            }
        }
    }

    private void cacheBitmapToFile(final Bitmap bmapIcon, final String cacheFileUri) {

        try (
            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            final FileOutputStream fout = new FileOutputStream(cacheFileUri)
        ) {
            bmapIcon.compress(Bitmap.CompressFormat.PNG, 100, stream);
            final byte[] byteArray = stream.toByteArray();
            fout.write(byteArray);
        } catch (final IOException e) {
            Log.error("Exception while caching icon bitmap.", e);
        }
    }

    public Bitmap getIcon(final String iconName) {
        Bitmap result = mIcons.get(iconName);

        //Reload it if it is no longer in the cache:
        if (result == null) {
            reloadIcon(iconName);
            result = mIcons.get(iconName);
        }

        return result;
    }
}