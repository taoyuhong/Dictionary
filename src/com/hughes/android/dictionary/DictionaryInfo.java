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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DictionaryInfo implements Serializable {
  
  private static final long serialVersionUID = -6850863377577700388L;
  
  public static final class IndexInfo {
    public IndexInfo(String langIso, int allTokenCount, int mainTokenCount) {
      this.langIso = langIso;
      this.allTokenCount = allTokenCount;
      this.mainTokenCount = mainTokenCount;
    }
    public final String langIso;
    public final int allTokenCount;
    public final int mainTokenCount;
    
    public static final int SIZE = 3;
    
    public StringBuilder append(StringBuilder result) {
      result.append("\t").append(langIso);
      result.append("\t").append(allTokenCount);
      result.append("\t").append(mainTokenCount);
      return result;
    }

    public IndexInfo(final String[] fields, int i) {
      langIso = fields[i++];
      allTokenCount = Integer.parseInt(fields[i++]);
      mainTokenCount = Integer.parseInt(fields[i++]);
    }

  }
  
  // Stuff populated from the text file.
  public String uncompressedFilename;
  public String downloadUrl;
  public long uncompressedSize;
  public long creationMillis;
  public String dictInfo;
  public final List<IndexInfo> indexInfos = new ArrayList<DictionaryInfo.IndexInfo>();

  String name;  // Determined at runtime based on locale on device--user editable?
  String localFile;  // Determined based on device's Environment.
  
  public StringBuilder append(final StringBuilder result) {
    result.append(uncompressedFilename).append("\t");
    result.append(downloadUrl).append("\t");
    result.append(creationMillis).append("\t");
    result.append(uncompressedSize).append("\t");
    result.append(dictInfo).append("\t");
    result.append(indexInfos.size()).append("\t");
    for (final IndexInfo indexInfo : indexInfos) {
      indexInfo.append(result);
    }
    return result;
  }

  public DictionaryInfo(final String line) {
    final String[] fields = line.split("\t");
    int i = 0;
    uncompressedFilename = fields[i++];
    downloadUrl = fields[i++];
    creationMillis = Long.parseLong(fields[i++]);
    uncompressedSize = Long.parseLong(fields[i++]);
    dictInfo = fields[i++];
    final int size = Integer.parseInt(fields[i++]);
    for (int j = 0; j < size; ++j) {
      indexInfos.add(new IndexInfo(fields, i));
      i += IndexInfo.SIZE;
    }
  }

  public DictionaryInfo() {
    // Blank object.
  }

  @Override
  public String toString() {
    return name;
  }


}
