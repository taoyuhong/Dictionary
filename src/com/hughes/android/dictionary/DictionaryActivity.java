// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.hughes.android.dictionary;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.Activity;
import android.app.Dialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.hughes.android.dictionary.engine.Dictionary;
import com.hughes.android.dictionary.engine.Index;
import com.hughes.android.dictionary.engine.PairEntry;
import com.hughes.android.dictionary.engine.PairEntry.Pair;
import com.hughes.android.dictionary.engine.RowBase;
import com.hughes.android.dictionary.engine.TokenRow;
import com.hughes.android.dictionary.engine.TransliteratorManager;
import com.hughes.android.util.PersistentObjectCache;

public class DictionaryActivity extends ListActivity {

  static final String LOG = "QuickDic";
  
  String dictFile = null;
  RandomAccessFile dictRaf = null;
  Dictionary dictionary = null;
  int indexIndex = 0;
  Index index = null;
  
  // package for test.
  final Handler uiHandler = new Handler();
  private final Executor searchExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
    @Override
    public Thread newThread(Runnable r) {
      return new Thread(r, "searchExecutor");
    }
  });
  private SearchOperation currentSearchOperation = null;

  C.Theme theme = C.Theme.LIGHT;
  int fontSizeSp;
  EditText searchText;
  Button langButton;

  // Never null.
  private File wordList = null;
  private boolean saveOnlyFirstSubentry = false;
  private boolean clickOpensContextMenu = false;

  // Visible for testing.
  ListAdapter indexAdapter = null;
  
  final SearchTextWatcher searchTextWatcher = new SearchTextWatcher();

  //private Vibrator vibrator = null;
  
  public DictionaryActivity() {
  }
  
  public static Intent getIntent(final Context context, final int dictIndex, final int indexIndex, final String searchToken) {
    setDictionaryPrefs(context, dictIndex, indexIndex, searchToken);
    final Intent intent = new Intent();
    intent.setClassName(DictionaryActivity.class.getPackage().getName(), DictionaryActivity.class.getName());
    return intent;
  }
  
  // TODO: Can we trigger an App restart when the preferences activity gets opened?
  // TODO: fix these...

  @Override
  protected void onSaveInstanceState(final Bundle outState) {
    super.onSaveInstanceState(outState);
    setDictionaryPrefs(this, dictIndex, indexIndex, searchText.getText().toString());
  }

  public static void setDictionaryPrefs(final Context context,
      final int dictIndex, final int indexIndex, final String searchToken) {
    final SharedPreferences.Editor prefs = PreferenceManager.getDefaultSharedPreferences(context).edit();
    prefs.putInt(C.DICT_INDEX, dictIndex);
    prefs.putInt(C.INDEX_INDEX, indexIndex);
    prefs.putString(C.SEARCH_TOKEN, searchToken);
    prefs.commit();
  }
  
  public static void clearDictionaryPrefs(final Context context) {
    final SharedPreferences.Editor prefs = PreferenceManager
        .getDefaultSharedPreferences(context).edit();
    prefs.remove(C.DICT_INDEX);
    prefs.remove(C.INDEX_INDEX);
    prefs.remove(C.SEARCH_TOKEN);
    prefs.commit();
    Log.d(LOG, "Removed default dictionary prefs.");
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    Log.d(LOG, "onCreate:" + this);
    theme = ((DictionaryApplication)getApplication()).getSelectedTheme();
    super.onCreate(savedInstanceState);
    
    final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    
    try {
      PersistentObjectCache.init(this);
      QuickDicConfig quickDicConfig = PersistentObjectCache.init(
          this).read(C.DICTIONARY_CONFIGS, QuickDicConfig.class);
      dictIndex = prefs.getInt(C.DICT_INDEX, 0) ;
      final DictionaryInfo dictionaryConfig = quickDicConfig.dictionaryInfos.get(dictIndex);
      this.setTitle("QuickDic: " + dictionaryConfig.name);
      dictRaf = new RandomAccessFile(dictionaryConfig.localFile, "r");
      dictionary = new Dictionary(dictRaf); 
    } catch (Exception e) {
      Log.e(LOG, "Unable to load dictionary.", e);
      if (dictRaf != null) {
        try {
          dictRaf.close();
        } catch (IOException e1) {
          Log.e(LOG, "Unable to close dictRaf.", e1);
        }
        dictRaf = null;
      }
      Toast.makeText(this, getString(R.string.invalidDictionary, "", e.getMessage()), Toast.LENGTH_LONG);
      startActivity(DictionaryManagerActivity.getIntent(this));
      finish();
      return;
    }

    indexIndex = prefs.getInt(C.INDEX_INDEX, 0) % dictionary.indices.size();
    Log.d(LOG, "Loading index.");
    index = dictionary.indices.get(indexIndex);
    setListAdapter(new IndexAdapter(index));

    // Pre-load the collators.
    searchExecutor.execute(new Runnable() {
      public void run() {
        final long startMillis = System.currentTimeMillis();
        
        TransliteratorManager.init(new TransliteratorManager.Callback() {
          @Override
          public void onTransliteratorReady() {
            uiHandler.post(new Runnable() {
              @Override
              public void run() {
                onSearchTextChange(searchText.getText().toString());
              }
            });
          }
        });
        
        for (final Index index : dictionary.indices) {
          Log.d(LOG, "Starting collator load for lang=" + index.sortLanguage.getIsoCode());
          
          final com.ibm.icu.text.Collator c = index.sortLanguage.getCollator();          
          if (c.compare("pre-print", "preppy") >= 0) {
            Log.e(LOG, c.getClass()
                + " is buggy, lookups may not work properly.");
          }
        }
        Log.d(LOG, "Loading collators took:"
            + (System.currentTimeMillis() - startMillis));
      }
    });
    
    final String fontSize = prefs.getString(getString(R.string.fontSizeKey), "12");
    try {
      fontSizeSp = Integer.parseInt(fontSize);
    } catch (NumberFormatException e) {
      fontSizeSp = 12;
    }

    setContentView(R.layout.dictionary_activity);
    searchText = (EditText) findViewById(R.id.SearchText);
    searchText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
    
    langButton = (Button) findViewById(R.id.LangButton);
    
    searchText.requestFocus();
    searchText.addTextChangedListener(searchTextWatcher);
    final String search = prefs.getString(C.SEARCH_TOKEN, "");
    searchText.setText(search);
    searchText.setSelection(0, search.length());
    Log.d(LOG, "Trying to restore searchText=" + search);
    
    final Button clearSearchTextButton = (Button) findViewById(R.id.ClearSearchTextButton);
    clearSearchTextButton.setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        onClearSearchTextButton(clearSearchTextButton);
      }
    });
    clearSearchTextButton.setVisibility(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
        getString(R.string.showClearSearchTextButtonKey), true) ? View.VISIBLE
        : View.GONE);
    
    final Button langButton = (Button) findViewById(R.id.LangButton);
    langButton.setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        onLanguageButton();
      }
    });
    langButton.setOnLongClickListener(new OnLongClickListener() {
      @Override
      public boolean onLongClick(View v) {
        onLanguageButtonLongClick();
        return true;
      }
    });
    updateLangButton();
    
    final Button upButton = (Button) findViewById(R.id.UpButton);
    upButton.setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        onUpDownButton(true);
      }
    });
    final Button downButton = (Button) findViewById(R.id.DownButton);
    downButton.setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        onUpDownButton(false);
      }
    });

   getListView().setOnItemSelectedListener(new ListView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> adapterView, View arg1, final int position,
          long id) {
        if (!searchText.isFocused()) {
          // TODO: don't do this if multi words are entered.
          final RowBase row = (RowBase) getListAdapter().getItem(position);
          Log.d(LOG, "onItemSelected: " + row.index());
          final TokenRow tokenRow = row.getTokenRow(true);
          searchText.setText(tokenRow.getToken());
        }
      }

      @Override
      public void onNothingSelected(AdapterView<?> arg0) {
      }
    });

    // ContextMenu.
    registerForContextMenu(getListView());

    // Prefs.
    wordList = new File(prefs.getString(getString(R.string.wordListFileKey),
        getString(R.string.wordListFileDefault)));
    saveOnlyFirstSubentry = prefs.getBoolean(getString(R.string.saveOnlyFirstSubentryKey), false);
    clickOpensContextMenu = prefs.getBoolean(getString(R.string.clickOpensContextMenuKey), false);
    //if (prefs.getBoolean(getString(R.string.vibrateOnFailedSearchKey), true)) {
      // vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    //}
    Log.d(LOG, "wordList=" + wordList + ", saveOnlyFirstSubentry=" + saveOnlyFirstSubentry);
  }
  
  @Override
  protected void onResume() {
    super.onResume();
    if (PreferenceActivity.prefsMightHaveChanged) {
      PreferenceActivity.prefsMightHaveChanged = false;
      finish();
      startActivity(getIntent());
    }
  }
  
  @Override
  protected void onPause() {
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (dictRaf == null) {
      return;
    }
    setDictionaryPrefs(this, dictIndex, indexIndex, searchText.getText().toString());
    
    // Before we close the RAF, we have to wind the current search down.
    if (currentSearchOperation != null) {
      Log.d(LOG, "Interrupting search to shut down.");
      final SearchOperation searchOperation = currentSearchOperation;
      currentSearchOperation = null;
      searchOperation.interrupted.set(true);
      synchronized (searchOperation) {
        while (!searchOperation.done) {
          try {
            searchOperation.wait();
          } catch (InterruptedException e) {
            Log.d(LOG, "Interrupted.", e);
          }
        }
      }
    }
    
    try {
      Log.d(LOG, "Closing RAF.");
      dictRaf.close();
    } catch (IOException e) {
      Log.e(LOG, "Failed to close dictionary", e);
    }
    dictRaf = null;
  }

  // --------------------------------------------------------------------------
  // Buttons
  // --------------------------------------------------------------------------

  private void onClearSearchTextButton(final Button clearSearchTextButton) {
    clearSearchTextButton.requestFocus();
    searchText.setText("");
    searchText.requestFocus();
    Log.d(LOG, "Trying to show soft keyboard.");
    final InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    manager.showSoftInput(searchText, InputMethodManager.SHOW_FORCED);
  }
  
  void updateLangButton() {
    langButton.setText(index.shortName);
  }

  void onLanguageButton() {
    if (currentSearchOperation != null) {
      currentSearchOperation.interrupted.set(true);
      currentSearchOperation = null;
    }
    
    changeIndex((indexIndex + 1)% dictionary.indices.size());
    onSearchTextChange(searchText.getText().toString());
  }
  
  static class OpenIndexButton extends Button implements OnClickListener {

    final Activity activity;
    final int dictionaryIndex;
    final int indexIndex;

    public OpenIndexButton(final Context context, final Activity activity, final String text, final int dictionaryIndex, final int indexIndex) {
      super(context);
      this.activity = activity;
      this.dictionaryIndex = dictionaryIndex;
      this.indexIndex = indexIndex;
      setOnClickListener(this);
      setText(text, BufferType.NORMAL);
    }

    @Override
    public void onClick(View v) {
      activity.finish();
      getContext().startActivity(DictionaryActivity.getIntent(getContext(), dictionaryIndex, indexIndex, ""));
    }
    
  }

  void onLanguageButtonLongClick() {
    Context mContext = getApplicationContext();
    Dialog dialog = new Dialog(mContext);
    
    dialog.setContentView(R.layout.select_dictionary_dialog);
    dialog.setTitle(R.string.selectADictionary);

    ListView listView = (ListView) dialog.findViewById(android.R.id.list);

    QuickDicConfig quickDicConfig = PersistentObjectCache.init(
        this).read(C.DICTIONARY_CONFIGS, QuickDicConfig.class);
    final List<DictionaryInfo> dictionaryInfos = new ArrayList<DictionaryInfo>();
    for (final DictionaryInfo dictionaryInfo : quickDicConfig.dictionaryInfos) {
      if (new File(dictionaryInfo.localFile).canRead()) {
        dictionaryInfos.add(dictionaryInfo);
      }
    }
    listView.setAdapter(new BaseAdapter() {
      
      @Override
      public View getView(int position, View convertView, ViewGroup parent) {
        final LinearLayout result = new LinearLayout(parent.getContext());
        result.addView(new Butt)
      }
      
      @Override
      public long getItemId(int position) {
        return position;
      }
      
      @Override
      public Object getItem(int position) {
        return dictionaryInfos.get(position);
      }
      
      @Override
      public int getCount() {
        return dictionaryInfos.size();
      }
    });
  }


  private void changeIndex(final int newIndex) {
    indexIndex = newIndex;
    index = dictionary.indices.get(indexIndex);
    indexAdapter = new IndexAdapter(index);
    Log.d(LOG, "changingIndex, newLang=" + index.longName);
    setListAdapter(indexAdapter);
    updateLangButton();
  }
  
  void onUpDownButton(final boolean up) {
    final int firstVisibleRow = getListView().getFirstVisiblePosition();
    final RowBase row = index.rows.get(firstVisibleRow);
    final TokenRow tokenRow = row.getTokenRow(true);
    final int destIndexEntry;
    if (up) {
      if (row != tokenRow) {
        destIndexEntry = tokenRow.referenceIndex;
      } else {
        destIndexEntry = Math.max(tokenRow.referenceIndex - 1, 0);
      }
    } else {
      // Down
      destIndexEntry = Math.min(tokenRow.referenceIndex + 1, index.sortedIndexEntries.size());
    }
    final Index.IndexEntry dest = index.sortedIndexEntries.get(destIndexEntry);
    Log.d(LOG, "onUpDownButton, destIndexEntry=" + dest.token);
    searchText.removeTextChangedListener(searchTextWatcher);
    searchText.setText(dest.token);
    jumpToRow(index.sortedIndexEntries.get(destIndexEntry).startRow);
    searchText.addTextChangedListener(searchTextWatcher);
  }

  // --------------------------------------------------------------------------
  // Options Menu
  // --------------------------------------------------------------------------
  
  @Override
  public boolean onCreateOptionsMenu(final Menu menu) {
    
    {
      final MenuItem preferences = menu.add(getString(R.string.preferences));
      preferences.setOnMenuItemClickListener(new OnMenuItemClickListener() {
        public boolean onMenuItemClick(final MenuItem menuItem) {
          PreferenceActivity.prefsMightHaveChanged = true;
          startActivity(new Intent(DictionaryActivity.this,
              PreferenceActivity.class));
          return false;
        }
      });
    }

    {
      final MenuItem dictionaryList = menu.add(getString(R.string.dictionaryManager));
      dictionaryList.setOnMenuItemClickListener(new OnMenuItemClickListener() {
        public boolean onMenuItemClick(final MenuItem menuItem) {
          startActivity(DictionaryManagerActivity.getIntent(DictionaryActivity.this));
          finish();
          return false;
        }
      });
    }

    {
      final MenuItem about = menu.add(getString(R.string.about));
      about.setOnMenuItemClickListener(new OnMenuItemClickListener() {
        public boolean onMenuItemClick(final MenuItem menuItem) {
          final Intent intent = new Intent().setClassName(AboutActivity.class
              .getPackage().getName(), AboutActivity.class.getCanonicalName());
          startActivity(intent);
          return false;
        }
      });
    }

    return true;
  }


  // --------------------------------------------------------------------------
  // Context Menu + clicks
  // --------------------------------------------------------------------------

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v,
      ContextMenuInfo menuInfo) {
    AdapterContextMenuInfo adapterContextMenuInfo = (AdapterContextMenuInfo) menuInfo;
    final RowBase row = (RowBase) getListAdapter().getItem(adapterContextMenuInfo.position);

    final MenuItem addToWordlist = menu.add(getString(R.string.addToWordList, wordList.getName()));
    addToWordlist.setOnMenuItemClickListener(new OnMenuItemClickListener() {
      public boolean onMenuItemClick(MenuItem item) {
        onAppendToWordList(row);
        return false;
      }
    });

    final MenuItem copy = menu.add(android.R.string.copy);
    copy.setOnMenuItemClickListener(new OnMenuItemClickListener() {
      public boolean onMenuItemClick(MenuItem item) {
        onCopy(row);
        return false;
      }
    });
    
    if (selectedSpannableText != null) {
      final String selectedText = selectedSpannableText;
      final MenuItem searchForSelection = menu.add(getString(R.string.searchForSelection, selectedSpannableText));
      searchForSelection.setOnMenuItemClickListener(new OnMenuItemClickListener() {
        public boolean onMenuItemClick(MenuItem item) {
          if (indexIndex != selectedSpannableIndex) {
            changeIndex(selectedSpannableIndex);
          }
          searchText.setText(selectedText);
          return false;
        }
      });
    }
    

  }
  
  @Override
  protected void onListItemClick(ListView l, View v, int row, long id) {
    defocusSearchText();
    
    if (clickOpensContextMenu && dictRaf != null) {
      openContextMenu(v);
    }
  }
  
  void onAppendToWordList(final RowBase row) {
    defocusSearchText();
    
    final StringBuilder rawText = new StringBuilder();
    rawText.append(
        new SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(new Date()))
        .append("\t");
    rawText.append(index.longName).append("\t");
    rawText.append(row.getTokenRow(true).getToken()).append("\t");
    rawText.append(row.getRawText(saveOnlyFirstSubentry));
    Log.d(LOG, "Writing : " + rawText);

    try {
      wordList.getParentFile().mkdirs();
      final PrintWriter out = new PrintWriter(
          new FileWriter(wordList, true));
      out.println(rawText.toString());
      out.close();
    } catch (IOException e) {
      Log.e(LOG, "Unable to append to " + wordList.getAbsolutePath(), e);
      Toast.makeText(this, getString(R.string.failedAddingToWordList, wordList.getAbsolutePath()), Toast.LENGTH_LONG);
    }
    return;
  }
  
  /**
   * Called when user clicks outside of search text, so that they can start
   * typing again immediately.
   */
  void defocusSearchText() {
    //Log.d(LOG, "defocusSearchText");
    // Request focus so that if we start typing again, it clears the text input.
    getListView().requestFocus();
    
    // Visual indication that a new keystroke will clear the search text.
    searchText.selectAll();
  }

  void onCopy(final RowBase row) {
    defocusSearchText();

    Log.d(LOG, "Copy, row=" + row);
    final StringBuilder result = new StringBuilder();
    result.append(row.getRawText(false));
    final ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    clipboardManager.setText(result.toString());
    Log.d(LOG, "Copied: " + result);
  }

  @Override
  public boolean onKeyDown(final int keyCode, final KeyEvent event) {
    if (event.getUnicodeChar() != 0) {
      if (!searchText.hasFocus()) {
        searchText.setText("" + (char) event.getUnicodeChar());
        onSearchTextChange(searchText.getText().toString());
        searchText.requestFocus();
        Selection.moveToRightEdge(searchText.getText(), searchText.getLayout());
      }
      return true;
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      Log.d(LOG, "Clearing dictionary prefs.");
      DictionaryActivity.clearDictionaryPrefs(this);
    }
    if (keyCode == KeyEvent.KEYCODE_ENTER) {
      Log.d(LOG, "Trying to hide soft keyboard.");
      final InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
      inputManager.hideSoftInputFromWindow(this.getCurrentFocus().getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }


  // --------------------------------------------------------------------------
  // SearchOperation
  // --------------------------------------------------------------------------

  private void searchFinished(final SearchOperation searchOperation) {
    if (searchOperation.interrupted.get()) {
      Log.d(LOG, "Search operation was interrupted: " + searchOperation);
      return;
    }
    if (searchOperation != this.currentSearchOperation) {
      Log.d(LOG, "Stale searchOperation finished: " + searchOperation);
      return;
    }
    
    final Index.IndexEntry searchResult = searchOperation.searchResult;
    Log.d(LOG, "searchFinished: " + searchOperation + ", searchResult=" + searchResult);

    currentSearchOperation = null;

    uiHandler.postDelayed(new Runnable() {
      @Override
      public void run() {
        if (currentSearchOperation == null) {
          jumpToRow(searchResult.startRow);
        } else {
          Log.d(LOG, "More coming, waiting for currentSearchOperation.");
        }
      }
    }, 50);
    
//    if (!searchResult.success) {
//      if (vibrator != null) {
//        vibrator.vibrate(VIBRATE_MILLIS);
//      }
//      searchText.setText(searchResult.longestPrefixString);
//      searchText.setSelection(searchResult.longestPrefixString.length());
//      return;
//    }
    
  }
  
  private final void jumpToRow(final int row) {
    setSelection(row);
    getListView().setSelected(true);
  }

  final class SearchOperation implements Runnable {
    
    final AtomicBoolean interrupted = new AtomicBoolean(false);
    final String searchText;
    final Index index;
    
    long searchStartMillis;

    Index.IndexEntry searchResult;
    
    boolean done = false;
    
    SearchOperation(final String searchText, final Index index) {
      this.searchText = searchText.trim();
      this.index = index;
    }
    
    public String toString() {
      return String.format("SearchOperation(%s,%s)", searchText, interrupted.toString());
    }

    @Override
    public void run() {
      try {
        searchStartMillis = System.currentTimeMillis();
        searchResult = index.findInsertionPoint(searchText, interrupted);
        Log.d(LOG, "searchText=" + searchText + ", searchDuration="
            + (System.currentTimeMillis() - searchStartMillis) + ", interrupted="
            + interrupted.get());
        if (!interrupted.get()) {
          uiHandler.post(new Runnable() {
            @Override
            public void run() {            
              searchFinished(SearchOperation.this);
            }
          });
        }
      } finally {
        synchronized (this) {
          done = true;
          this.notifyAll();
        }
      }
    }
  }

  
  // --------------------------------------------------------------------------
  // IndexAdapter
  // --------------------------------------------------------------------------

  final class IndexAdapter extends BaseAdapter {
    
    final Index index;

    IndexAdapter(final Index index) {
      this.index = index;
    }

    @Override
    public int getCount() {
      return index.rows.size();
    }

    @Override
    public RowBase getItem(int position) {
      return index.rows.get(position);
    }

    @Override
    public long getItemId(int position) {
      return getItem(position).index();
    }

    @Override
    public View getView(int position, final View convertView, ViewGroup parent) {
      final RowBase row = index.rows.get(position);
      if (row instanceof PairEntry.Row) {
        return getView((PairEntry.Row) row, parent, convertView);
      } else if (row instanceof TokenRow) {
        return getView((TokenRow) row, parent, convertView);
      } else {
        throw new IllegalArgumentException("Unsupported Row type: " + row.getClass());
      }
    }

    private View getView(PairEntry.Row row, ViewGroup parent, final View convertView) {
      final TableLayout result = new TableLayout(parent.getContext());
      final PairEntry entry = row.getEntry();
      final int rowCount = entry.pairs.size();
      for (int r = 0; r < rowCount; ++r) {
        final TableRow tableRow = new TableRow(result.getContext());

        final TextView col1 = new TextView(tableRow.getContext());
        final TextView col2 = new TextView(tableRow.getContext());
        final TableRow.LayoutParams layoutParams = new TableRow.LayoutParams();
        layoutParams.weight = 0.5f;

        // Set the columns in the table.
        if (r > 0) {
          final TextView bullet = new TextView(tableRow.getContext());
          bullet.setText(" • ");
          tableRow.addView(bullet);
        }
        tableRow.addView(col1, layoutParams);
        final TextView margin = new TextView(tableRow.getContext());
        margin.setText(" ");
        tableRow.addView(margin);
        if (r > 0) {
          final TextView bullet = new TextView(tableRow.getContext());
          bullet.setText(" • ");
          tableRow.addView(bullet);
        }
        tableRow.addView(col2, layoutParams);
        col1.setWidth(1);
        col2.setWidth(1);
        
        // Set what's in the columns.

        // TODO: color words by gender
        final Pair pair = entry.pairs.get(r);
        final String col1Text = index.swapPairEntries ? pair.lang2 : pair.lang1;
        final String col2Text = index.swapPairEntries ? pair.lang1 : pair.lang2;
        
        col1.setText(col1Text, TextView.BufferType.SPANNABLE);
        col2.setText(col2Text, TextView.BufferType.SPANNABLE);
        
        // Bold the token instances in col1.
        final Spannable col1Spannable = (Spannable) col1.getText();
        int startPos = 0;
        final String token = row.getTokenRow(true).getToken();
        while ((startPos = col1Text.indexOf(token, startPos)) != -1) {
          col1Spannable.setSpan(new StyleSpan(Typeface.BOLD), startPos,
              startPos + token.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
          startPos += token.length();
        }
        
        createTokenLinkSpans(col1, col1Spannable, col1Text);
        createTokenLinkSpans(col2, (Spannable) col2.getText(), col2Text);
        
        col1.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        col2.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        // col2.setBackgroundResource(theme.otherLangBg);
        
        if (index.swapPairEntries) {
          col2.setOnLongClickListener(textViewLongClickListenerIndex0);
          col1.setOnLongClickListener(textViewLongClickListenerIndex1);
        } else {
          col1.setOnLongClickListener(textViewLongClickListenerIndex0);
          col2.setOnLongClickListener(textViewLongClickListenerIndex1);
        }
        
        result.addView(tableRow);
      }

      return result;

      
//      final WebView result = (WebView) (convertView instanceof WebView ? convertView : new WebView(parent.getContext()));
//        
//      final PairEntry entry = row.getEntry();
//      final int rowCount = entry.pairs.size();
//      final StringBuilder html = new StringBuilder();
//      html.append("<html><body><table width=\"100%\">");
//      for (int r = 0; r < rowCount; ++r) {
//        html.append("<tr>");
//
//        final Pair pair = entry.pairs.get(r);
//        // TODO: escape both the token and the text.
//        final String token = row.getTokenRow(true).getToken();
//        final String col1Text = index.swapPairEntries ? pair.lang2 : pair.lang1;
//        final String col2Text = index.swapPairEntries ? pair.lang1 : pair.lang2;
//        
//        col1Text.replaceAll(token, String.format("<b>%s</b>", token));
//
//        // Column1
//        html.append("<td width=\"50%\">");
//        if (r > 0) {
//          html.append("<li>");
//        }
//        html.append(col1Text);
//        html.append("</td>");
//
//        // Column2
//        html.append("<td width=\"50%\">");
//        if (r > 0) {
//          html.append("<li>");
//        }
//        html.append(col2Text);
//        html.append("</td>");
//
////        column1.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
////        column2.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
//
//        html.append("</tr>");
//      }
//      html.append("</table></body></html>");
//      
//      Log.i(LOG, html.toString());
//      
//      result.getSettings().setRenderPriority(RenderPriority.HIGH);
//      result.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
//      
//      result.loadData("<html><body><table><tr><td>line (connected series of public conveyances, and hence, an established arrangement for forwarding merchandise, etc.) (noun)</td><td>verbinding</td></tr></table></body></html>", "text/html", "utf-8");
//
//      return result;
    }

    private View getView(TokenRow row, ViewGroup parent, final View convertView) {
      final Context context = parent.getContext();
      final TextView textView = new TextView(context);
      textView.setText(row.getToken());
      textView.setBackgroundResource(row.hasMainEntry ? theme.tokenRowMainBg : theme.tokenRowOtherBg);
      // Doesn't work:
      //textView.setTextColor(android.R.color.secondary_text_light);
      textView.setTextAppearance(context, theme.tokenRowFg);
      textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 5 * fontSizeSp / 4);
      return textView;
    }
    
  }

  static final Pattern CHAR_DASH = Pattern.compile("['\\p{L}0-9]+");

  private void createTokenLinkSpans(final TextView textView, final Spannable spannable, final String text) {
    // Saw from the source code that LinkMovementMethod sets the selection!
    // http://grepcode.com/file/repository.grepcode.com/java/ext/com.google.android/android/2.3.1_r1/android/text/method/LinkMovementMethod.java#LinkMovementMethod
    textView.setMovementMethod(LinkMovementMethod.getInstance());
    final Matcher matcher = CHAR_DASH.matcher(text);
    while (matcher.find()) {
      spannable.setSpan(new MyClickableSpan(), matcher.start(), matcher.end(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
    }
  }
  

  String selectedSpannableText = null;
  int selectedSpannableIndex = -1;

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    selectedSpannableText = null;
    selectedSpannableIndex = -1;
    return super.onTouchEvent(event);
  }

  private class TextViewLongClickListener implements OnLongClickListener {
    final int index;
    
    private TextViewLongClickListener(final int index) {
      this.index = index;
    }

    @Override
    public boolean onLongClick(final View v) {
      final TextView textView = (TextView) v;
      final int start = textView.getSelectionStart();
      final int end = textView.getSelectionEnd();
      if (start >= 0 &&  end >= 0) {
        selectedSpannableText = textView.getText().subSequence(start, end).toString();
        selectedSpannableIndex = index;
      }
      return false;
    }
  }
  final TextViewLongClickListener textViewLongClickListenerIndex0 = new TextViewLongClickListener(0);
  final TextViewLongClickListener textViewLongClickListenerIndex1 = new TextViewLongClickListener(1);
  

  // --------------------------------------------------------------------------
  // SearchText
  // --------------------------------------------------------------------------

  void onSearchTextChange(final String text) {
    if (dictRaf == null) {
      Log.d(LOG, "searchText changed during shutdown, doing nothing.");
      return;
    }
    if (!searchText.isFocused()) {
      Log.d(LOG, "searchText changed without focus, doing nothing.");
      return;
    }
    Log.d(LOG, "onSearchTextChange: " + text);    
    if (currentSearchOperation != null) {
      Log.d(LOG, "Interrupting currentSearchOperation.");
      currentSearchOperation.interrupted.set(true);
    }
    currentSearchOperation = new SearchOperation(text, index);
    searchExecutor.execute(currentSearchOperation);
  }
  
  private class SearchTextWatcher implements TextWatcher {
    public void afterTextChanged(final Editable searchTextEditable) {
      if (searchText.hasFocus()) {
        Log.d(LOG, "Search text changed with focus: " + searchText.getText());
        // If they were typing to cause the change, update the UI.
        onSearchTextChange(searchText.getText().toString());
      }
    }

    public void beforeTextChanged(CharSequence arg0, int arg1, int arg2,
        int arg3) {
    }

    public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
    }
  }

}
