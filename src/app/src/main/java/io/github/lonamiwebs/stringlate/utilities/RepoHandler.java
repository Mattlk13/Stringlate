package io.github.lonamiwebs.stringlate.utilities;

import android.content.Context;
import android.os.AsyncTask;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.lonamiwebs.stringlate.interfaces.Callback;
import io.github.lonamiwebs.stringlate.interfaces.ProgressUpdateCallback;
import io.github.lonamiwebs.stringlate.R;
import io.github.lonamiwebs.stringlate.classes.resources.Resources;

// Class used to inter-operate with locally saved GitHub "repositories"
// What is stored are simply the strings.xml file under a tree directory structure:
/*
owner1/
       repo1/
             strings.xml
             strings-es.xml
       repo2/
             ...
owner2/
       ...
* */
public class RepoHandler {

    //region Members

    private final Context mContext;
    private final String mOwner, mRepo;

    private final File mRoot;

    private final Pattern mValuesLocalePattern; // Match locale from "res/values-(...)/strings.xml"
    private final Pattern mLocalesPattern; // Match locale from "strings-(...).xml"
    private final ArrayList<String> mLocales;

    private static final String BASE_DIR = "repos";
    private static final String RAW_FILE_URL = "https://raw.githubusercontent.com/%s/%s/master/%s";

    private static final String GITHUB_REPO_URL = "https://github.com/%s/%s";

    public static final String DEFAULT_LOCALE = "default";

    //endregion

    //region Constructors

    public RepoHandler(Context context, String owner, String repo) {
        mContext = context;
        mOwner = owner;
        mRepo = repo;

        mRoot = new File(mContext.getFilesDir(), BASE_DIR+"/"+mOwner+"/"+mRepo);

        mValuesLocalePattern = Pattern.compile(
                "res/values(?:-([\\w-]+))?/strings\\.xml");

        mLocalesPattern = Pattern.compile("strings(?:-([\\w-]+))?\\.xml");
        mLocales = new ArrayList<>();
        loadLocales();
    }

    //endregion

    //region Utilities

    // Retrieves the File object for the given locale
    private File getResourcesFile(String locale) {
        if (locale == null || locale.equals(DEFAULT_LOCALE))
            return new File(mRoot, "strings.xml");
        else
            return new File(mRoot, "strings-"+locale+".xml");
    }

    // Determines whether a given locale is saved or not
    public boolean hasLocale(String locale) { return getResourcesFile(locale).isFile(); }

    // Determines whether a given locale has been modified or not
    public boolean hasModifiedLocale(String locale) {
        if (mLocales.contains(locale))
            return Resources.fromFile(getResourcesFile(locale)).wasModified();
        else
            return false;
    }

    // Determines whether any file has been modified,
    // i.e. it is not the original downloaded file any more
    public boolean anyModified() {
        for (String locale : mLocales)
            if (Resources.fromFile(getResourcesFile(locale)).wasModified())
                return true;
        return false;
    }

    // Determines whether the repository is empty (has no saved locales) or not
    public boolean isEmpty() { return mLocales.isEmpty(); }

    // Deletes the repository erasing its existence from Earth. Forever. (Unless added again)
    public void delete() {
        for (File file : mRoot.listFiles())
            file.delete();

        mRoot.delete();
        if (mRoot.getParentFile().listFiles().length == 0)
            mRoot.getParentFile().delete();
    }

    //endregion

    //region Locales

    //region Loading locale files

    private void loadLocales() {
        mLocales.clear();
        if (mRoot.isDirectory()) {
            for (File f : mRoot.listFiles()) {
                String path = f.getAbsolutePath();
                Matcher m = mLocalesPattern.matcher(path);
                if (m.find())
                    mLocales.add(m.group(1) == null ? DEFAULT_LOCALE : m.group(1));
            }
        }
    }

    public ArrayList<String> getLocales() {
        return mLocales;
    }

    //endregion

    //region Creating and deleting locale files

    public boolean createLocale(String locale) {
        if (hasLocale(locale))
            return true;

        Resources resources = Resources.fromFile(getResourcesFile(locale));
        if (resources == null || !resources.save())
            return false;

        mLocales.add(locale);
        return true;
    }

    public void deleteLocale(String locale) {
        if (hasLocale(locale)) {
            Resources.fromFile(getResourcesFile(locale)).delete();
            mLocales.remove(locale);
        }
    }

    //endregion

    //region Downloading locale files

    public void syncResources(ProgressUpdateCallback callback, boolean overwrite) {
        scanStringsXml(callback, overwrite);
    }

    // Step 1: Scans the current repository to find strings.xml files
    //         If any file is found, its remote path and locale name is added to a list,
    //         and the Step 2. is called (downloadLocales).
    private void scanStringsXml(final ProgressUpdateCallback callback, final boolean overwrite) {
        callback.onProgressUpdate(
                mContext.getString(R.string.scanning_repository),
                mContext.getString(R.string.scanning_repository_long));

        // We want to find files on the owner/repo repository
        // containing 'resources' ('<resources>') on them and the filename
        // being 'strings.xml'. Some day Java will have named parameters...
        GitHub.gFindFiles(mOwner, mRepo, "resources", "strings.xml", new Callback<Object>() {
            @Override
            public void onCallback(Object o) {
                ArrayList<String> remotePaths = new ArrayList<>();
                ArrayList<String> locales = new ArrayList<>();
                try {
                    JSONObject json = (JSONObject) o;
                    JSONArray items = json.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        Matcher m = mValuesLocalePattern.matcher(item.getString("path"));
                        if (m.find()) {
                            String locale = m.group(1) == null ? DEFAULT_LOCALE : m.group(1);
                            if (overwrite || !hasModifiedLocale(locale)) {
                                remotePaths.add(item.getString("path"));
                                locales.add(m.group(1) == null ? DEFAULT_LOCALE : m.group(1));
                            }
                        }
                    }
                    if (remotePaths.size() == 0) {
                        callback.onProgressFinished(
                                mContext.getString(R.string.no_strings_found), false);
                    } else {
                        downloadLocales(remotePaths, locales, callback);
                    }
                } catch (JSONException e) {
                    callback.onProgressFinished(
                            mContext.getString(R.string.error_parsing_json), false);
                }
            }
        });
    }

    // Step 2: Given the remote paths of the strings.xml files,
    //         download them to our local "repository".
    private void downloadLocales(final ArrayList<String> remotePaths,
                                 final ArrayList<String> locales,
                                 final ProgressUpdateCallback callback) {
        callback.onProgressUpdate(
                mContext.getString(R.string.downloading_strings_locale, 0, remotePaths.size()),
                mContext.getString(R.string.downloading_to_translate));

        new AsyncTask<Void, Integer, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                for (int i = 0; i < remotePaths.size(); i++) {
                    publishProgress(i);
                    downloadLocale(remotePaths.get(i), locales.get(i));
                }
                return null;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                int i = values[0];
                callback.onProgressUpdate(
                        mContext.getString(R.string.downloading_strings_locale, i+1, remotePaths.size()),
                        mContext.getString(R.string.downloading_strings_locale_description, locales.get(i)));

                super.onProgressUpdate(values);
            }

            @Override
            protected void onPostExecute(Void v) {
                loadLocales();
                callback.onProgressFinished(null, true);
                super.onPostExecute(v);
            }
        }.execute();
    }

    // Downloads a single locale file to our local "repository"
    public void downloadLocale(String remotePath, String locale) {
        final String urlString = String.format(RAW_FILE_URL, mOwner, mRepo, remotePath);
        final File outputFile = getResourcesFile(locale);

        FileDownloader.downloadFile(urlString, outputFile);
    }

    //endregion

    //region Loading resources

    public Resources loadResources(String locale) {
        return Resources.fromFile(getResourcesFile(locale));
    }

    //endregion

    //endregion

    //region Static repository listing

    // Lists all the owners under the base directory
    public static ArrayList<String> listOwners(Context context) {
        ArrayList<String> owners = new ArrayList<>();

        File root = new File(context.getFilesDir(), BASE_DIR);
        if (root.isDirectory())
            for (File f : root.listFiles())
                if (f.isDirectory())
                    owners.add(f.getName());

        return owners;
    }

    // Lists all the repositories of the given owner
    public static ArrayList<String> listRepositories(Context context, String owner) {
        ArrayList<String> repositories = new ArrayList<>();

        File root = new File(context.getFilesDir(), BASE_DIR+"/"+owner);
        if (root.isDirectory())
            for (File f : root.listFiles())
                if (f.isDirectory())
                    repositories.add(f.getName());

        return repositories;
    }

    // Lists all the repositories of all the owners under the base directory
    // and returns a list to their URLs at GitHub
    public static ArrayList<String> listRepositories(Context context) {
        ArrayList<String> repositories = new ArrayList<>();
        for (String owner : listOwners(context)) {
            for (String repository : listRepositories(context, owner)) {
                repositories.add(String.format(GITHUB_REPO_URL, owner, repository));
            }
        }
        return repositories;
    }

    //endregion

    //region To string

    @Override
    public String toString() {
        return mOwner+"/"+mRepo;
    }

    //endregion
}