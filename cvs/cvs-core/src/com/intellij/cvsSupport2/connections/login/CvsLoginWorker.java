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
package com.intellij.cvsSupport2.connections.login;

import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.CalledInBackground;
import com.intellij.util.ThreeState;
import org.netbeans.lib.cvsclient.connection.AuthenticationException;

// todo rename?
public interface CvsLoginWorker {
  /**
   * @return {@code true} if login attempt should be repeated after prompting user
   */
  @RequiresEdt
  boolean promptForPassword();

  @RequiresBackgroundThread
  ThreeState silentLogin(boolean forceCheck) throws AuthenticationException;

  void goOffline();
}
