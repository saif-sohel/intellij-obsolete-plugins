/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.cvsSupport2.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * author: lesya
 */
public class LocalSettings implements PersistentStateComponent<LocalSettings>, Cloneable {

  private static final Logger LOG = Logger.getInstance(LocalSettings.class);

  @NonNls
  public String PATH_TO_CVS_CLIENT = "cvs";

  private boolean myCvsClientVerified = false;

  @Override
  public @Nullable LocalSettings getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull LocalSettings state) {
    XmlSerializerUtil.copyBean(state, this);
  }

  public boolean isCvsClientVerified() {
    return myCvsClientVerified;
  }

  public void setCvsClientVerified(final boolean cvsClientVerified) {
    myCvsClientVerified = cvsClientVerified;
  }

  @Override
  public LocalSettings clone() {
    try {
      return (LocalSettings)super.clone();
    }
    catch (CloneNotSupportedException e) {
      LOG.error(e);
      return new LocalSettings();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    return PATH_TO_CVS_CLIENT.equals(((LocalSettings)o).PATH_TO_CVS_CLIENT);
  }

  @Override
  public int hashCode() {
    return PATH_TO_CVS_CLIENT.hashCode();
  }
}
