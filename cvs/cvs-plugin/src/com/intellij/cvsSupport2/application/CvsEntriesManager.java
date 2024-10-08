// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.application;

import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration;
import com.intellij.cvsSupport2.config.CvsRootConfiguration;
import com.intellij.cvsSupport2.connections.CvsConnectionSettings;
import com.intellij.cvsSupport2.connections.IDEARootFormatter;
import com.intellij.cvsSupport2.cvsIgnore.IgnoredFilesInfo;
import com.intellij.cvsSupport2.cvsIgnore.UserDirIgnores;
import com.intellij.cvsSupport2.cvsstatuses.CvsEntriesListener;
import com.intellij.cvsSupport2.util.CvsVfsUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.*;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netbeans.lib.cvsclient.admin.Entry;

import java.io.File;
import java.util.Collection;
import java.util.Map;

/**
 * author: lesya
 */

public class CvsEntriesManager implements VirtualFileListener {
  private static final Logger LOG = Logger.getInstance(CvsEntriesManager.class);

  private final Map<VirtualFile, CvsInfo> myInfoByParentDirectoryPath = new THashMap<>();

  private static final String CVS_ADMIN_DIRECTORY_NAME = CvsUtil.CVS;

  private final Collection<CvsEntriesListener> myEntriesListeners = ContainerUtil.createLockFreeCopyOnWriteList();
  private int myIsActive; // guarded by this
  private final Collection<String> myFilesToRefresh = new THashSet<>();

  private final Map<String, CvsConnectionSettings> myStringToSettingsMap = new THashMap<>();
  private final UserDirIgnores myUserDirIgnores = new UserDirIgnores();
  private Disposable listenerDisposable;

  public static CvsEntriesManager getInstance() {
    return ApplicationManager.getApplication().getService(CvsEntriesManager.class);
  }

  private class MyVirtualFileManagerListener implements VirtualFileManagerListener {
    @Override
    public void afterRefreshFinish(boolean asynchronous) {
      ensureFilesCached(); //to cache for next refreshes
    }
  }

  public synchronized void activate() {
    if (myIsActive == 0) {
      listenerDisposable = Disposer.newDisposable();
      VirtualFileManager.getInstance().addVirtualFileListener(this, listenerDisposable);
      VirtualFileManager.getInstance().addVirtualFileManagerListener(new MyVirtualFileManagerListener(), listenerDisposable);
    }
    myIsActive++;
  }

  public synchronized void deactivate() {
    LOG.assertTrue(isActive());
    myIsActive--;
    if (myIsActive == 0) {
      myInfoByParentDirectoryPath.clear();
      Disposer.dispose(listenerDisposable);
      listenerDisposable = null;
    }
  }

  @Override
  public void beforePropertyChange(@NotNull VirtualFilePropertyEvent event) {
    processEvent(event);
  }

  @Override
  public void beforeContentsChange(@NotNull VirtualFileEvent event) {
    processEvent(event);
  }

  @Override
  public void contentsChanged(@NotNull VirtualFileEvent event) {
    fireStatusChanged(event.getFile());
  }


  @NotNull
  private synchronized CvsInfo getInfoFor(VirtualFile parent) {
    if (parent == null) return CvsInfo.getDummyCvsInfo();
    if (!myInfoByParentDirectoryPath.containsKey(parent)) {
      CvsInfo cvsInfo = new CvsInfo(parent);
      myInfoByParentDirectoryPath.put(cvsInfo.getKey(), cvsInfo);
    }
    return myInfoByParentDirectoryPath.get(parent);
  }

  public synchronized void clearCachedFiltersFor(final VirtualFile parent) {
    for (final VirtualFile file : myInfoByParentDirectoryPath.keySet()) {
      if (file == null) continue;
      if (!file.isValid()) continue;
      if (VfsUtilCore.isAncestor(parent, file, false)) {
        myInfoByParentDirectoryPath.get(file).clearFilter();
      }
    }
    fileStatusesChanged();
  }

  private static void fileStatusesChanged() {
    Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
    for (Project project : openProjects) {
      VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
    }
  }

  private static boolean isCvsIgnoreFile(VirtualFile file) {
    return CvsUtil.CVS_IGNORE_FILE.equals(file.getName());
  }

  public IgnoredFilesInfo getFilter(VirtualFile parent) {
    return getInfoFor(parent).getIgnoreFilter();
  }

  @Override
  public void beforeFileDeletion(@NotNull VirtualFileEvent event) {
    processEvent(event);
  }

  @Override
  public void beforeFileMovement(@NotNull VirtualFileMoveEvent event) {
    processEvent(event);
  }

  @Override
  public void fileCreated(@NotNull VirtualFileEvent event) {
    processEvent(event);
  }

  private void processEvent(VirtualFileEvent event) {
    VirtualFile file = event.getFile();

    if (isUserHomeCvsIgnoreFile(file)) {
      myUserDirIgnores.clearInfo();
      fileStatusesChanged();
      return;
    }

    if (isCvsIgnoreFile(file)) {
      clearCachedFiltersFor(file.getParent());
      return;
    }

    if (isCvsAdminDir(file.getParent())) {
      VirtualFile parent = file.getParent().getParent();
      clearCachedEntriesFor(parent);
      return;
    }

    if (isCvsAdminDir(file)) {
      clearCachedEntriesFor(file.getParent());
      return;
    }

    if (file.isDirectory()) {
      clearCachedEntriesRecursive(file);
    }
  }

  private static boolean isCvsAdminDir(VirtualFile file) {
    if (file == null) return false;
    return file.isDirectory() && CVS_ADMIN_DIRECTORY_NAME.equals(file.getName());
  }

  private synchronized void clearCachedEntriesRecursive(VirtualFile parent) {
    if (!parent.isDirectory()) return;

    for (final VirtualFile file : myInfoByParentDirectoryPath.keySet()) {
      if (file == null) continue;
      if (!file.isValid()) continue;
      if (VfsUtilCore.isAncestor(parent, file, false)) clearCachedEntriesFor(file);
    }
  }

  public Entry getEntryFor(VirtualFile parent, String name) {
    return getCvsInfo(parent).getEntryNamed(name);
  }

  public Entry getEntryFor(@NotNull VirtualFile file) {
    final CvsInfo cvsInfo = getCvsInfo(file.getParent());
    assert cvsInfo != null;
    return cvsInfo.getEntryNamed(file.getName());
  }

  public void clearCachedEntriesFor(final VirtualFile parent) {
    if (parent == null) return;

    CvsInfo cvsInfo = getInfoFor(parent);
    cvsInfo.clearFilter();
    if (cvsInfo.isLoaded()) {
      cvsInfo.clearAll();
      ApplicationManager.getApplication().invokeLater(() -> {
        if (parent.isValid()) {
          onEntriesChanged(parent);
        }
      });
    }
  }

  @Nullable
  public Entry getCachedEntry(VirtualFile parent, String fileName){
    if (parent == null) return null;

    CvsInfo cvsInfo = getInfoFor(parent);

    if (!cvsInfo.isLoaded()) return null;
    return cvsInfo.getEntryNamed(fileName);
  }

  public void setEntryForFile(final VirtualFile parent, final Entry entry) {
    if (parent == null) return;

    CvsInfo cvsInfo = getInfoFor(parent);

    if (!cvsInfo.isLoaded()) return;

    cvsInfo.setEntryAndReturnReplacedEntry(entry);

    ApplicationManager.getApplication().invokeLater(() -> {
      final VirtualFile file = CvsVfsUtil.findChild(parent, entry.getFileName());
      if (file != null) {
        onEntryChanged(file);
      }
    });
  }

  public void removeEntryForFile(final File parent, final String fileName) {
    CvsInfo cvsInfo = getInfoFor(CvsVfsUtil.findFileByIoFile(parent));
    if (!cvsInfo.isLoaded()) return;

    cvsInfo.removeEntryNamed(fileName);

    final VirtualFile[] file = new VirtualFile[1];

    ApplicationManager.getApplication().invokeLater(() -> {
      ApplicationManager.getApplication().runReadAction(() -> {
        file[0] = LocalFileSystem.getInstance().findFileByIoFile(new File(parent, fileName));
      });
      if (file[0] != null) {
        onEntryChanged(file[0]);
      }
    });
  }

  private void onEntriesChanged(final VirtualFile parent) {
    for (CvsEntriesListener listener : myEntriesListeners) {
      listener.entriesChanged(parent);
    }
  }

  private void onEntryChanged(final VirtualFile file) {
    for (CvsEntriesListener listener : myEntriesListeners) {
      listener.entryChanged(file);
    }
  }

  void watchForCvsAdminFiles(final VirtualFile parent) {
    if (parent == null) return;
    synchronized (myFilesToRefresh) {
      myFilesToRefresh.add(parent.getPath() + "/" + CVS_ADMIN_DIRECTORY_NAME);
    }
  }


  public Collection<Entry> getEntriesIn(VirtualFile parent) {
    return getCvsInfo(parent).getEntries();

  }

  private CvsInfo getCvsInfo(VirtualFile parent) {
    if (! isActive()) {
      throw new ProcessCanceledException();
    }
    if (parent == null) return CvsInfo.getDummyCvsInfo();
    return getInfoFor(parent);
  }

  public void addCvsEntriesListener(CvsEntriesListener listener) {
    myEntriesListeners.add(listener);
  }

  public void removeCvsEntriesListener(CvsEntriesListener listener) {
    myEntriesListeners.remove(listener);
  }

  public synchronized void clearAll() {
    myInfoByParentDirectoryPath.clear();
  }

  public boolean fileIsIgnored(VirtualFile file) {
    final VirtualFile parent = file.getParent();
    if (parent == null) {
      return false;
    }
    if (CvsUtil.fileIsUnderCvs(file)) return false;
    return getFilter(parent).shouldBeIgnored(file);
  }

  private void ensureFilesCached() {
    final String[] paths;
    synchronized (myFilesToRefresh) {
      paths = ArrayUtilRt.toStringArray(myFilesToRefresh);
      myFilesToRefresh.clear();
    }
    for (String path : paths) {
      final VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(path);
      if (virtualFile != null) virtualFile.getChildren();
    }
  }

  public CvsConnectionSettings getCvsConnectionSettingsFor(VirtualFile root) {
    return getInfoFor(root).getConnectionSettings();
  }

  public CvsConnectionSettings getCvsConnectionSettingsFor(@NotNull File root) {
    return getCvsConnectionSettingsFor(CvsVfsUtil.refreshAndFindFileByIoFile(root));
  }

  public CvsInfo getCvsInfoFor(VirtualFile directory) {
    return getInfoFor(directory);
  }

  CvsConnectionSettings createConnectionSettingsOn(String cvsRoot) {
    if (!myStringToSettingsMap.containsKey(cvsRoot)) {
      final CvsRootConfiguration rootConfiguration = CvsApplicationLevelConfiguration.getInstance().getConfigurationForCvsRoot(cvsRoot);
      CvsConnectionSettings settings = new IDEARootFormatter(rootConfiguration).createConfiguration();
      myStringToSettingsMap.put(cvsRoot, settings);
    }
    return myStringToSettingsMap.get(cvsRoot);
  }

  public UserDirIgnores getUserDirIgnores() {
    return myUserDirIgnores;
  }

  private static void fireStatusChanged(VirtualFile file) {
    Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
    for (Project project : openProjects) {
      FileStatusManager.getInstance(project).fileStatusChanged(file);
      VcsDirtyScopeManager.getInstance(project).fileDirty(file);
    }
  }

  private static boolean isUserHomeCvsIgnoreFile(VirtualFile file) {
    return UserDirIgnores.userHomeCvsIgnoreFile().equals(CvsVfsUtil.getFileFor(file));
  }

  public synchronized boolean isActive() {
    return myIsActive > 0;
  }

  public String getRepositoryFor(VirtualFile root) {
    return getInfoFor(root).getRepository();
  }

  public void cacheCvsAdminInfoIn(VirtualFile root) {
    getInfoFor(root).cacheAll();
  }

  public String getStickyTagFor(VirtualFile fileByIoFile) {
    return getCvsInfo(fileByIoFile).getStickyTag();
  }

  public void encodingChanged() {
    if (!isActive()) return;
    clearAll();
    fileStatusesChanged();
  }
}


