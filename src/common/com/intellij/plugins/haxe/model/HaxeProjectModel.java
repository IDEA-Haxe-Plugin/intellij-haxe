/*
 * Copyright 2017-2017 Ilya Malanin
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
package com.intellij.plugins.haxe.model;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class HaxeProjectModel {
  static private Key<HaxeProjectModel> HAXE_PROJECT_MODEL = new Key<>("HAXE_PROJECT_MODEL");

  private final Project project;
  private final HaxePackageModel stdPackage;
  private final HaxeSourceRootModel sdkRoot;

  private HaxeProjectModel(Project project) {
    this.project = project;
    this.sdkRoot = resolveSdkRoot();
    this.stdPackage = new HaxeStdPackageModel(this, getSdkRoot());
  }

  private HaxeSourceRootModel resolveSdkRoot() {
    final VirtualFile[] roots;

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      roots = OrderEnumerator.orderEntries(project).getAllSourceRoots();
      if (roots.length > 0) {
        VirtualFile stdRootForTests = roots[0].findChild("std");
        if (stdRootForTests != null) {
          return new HaxeSourceRootModel(this, stdRootForTests);
        }
      }
    }
    else {
      roots = OrderEnumerator.orderEntries(project).sdkOnly().getAllSourceRoots();
      if (roots.length > 0) {
        return new HaxeSourceRootModel(this, roots[0]);
      }
    }
    return HaxeSourceRootModel.DUMMY;
  }

  public static HaxeProjectModel fromElement(PsiElement element) {
    return fromProject(element.getProject());
  }

  public static HaxeProjectModel fromProject(Project project) {
    HaxeProjectModel model = project.getUserData(HAXE_PROJECT_MODEL);
    if (model == null) {
      project.putUserData(HAXE_PROJECT_MODEL, model = new HaxeProjectModel(project));
    }
    return model;
  }

  public Project getProject() {
    return project;
  }

  public String getName() {
    return project.getName();
  }

  public List<HaxeSourceRootModel> getRoots() {
    ArrayList<HaxeSourceRootModel> out = new ArrayList<>();

    for (VirtualFile sourceRoot : OrderEnumerator.orderEntries(project).withoutSdk().getAllSourceRoots()) {
      out.add(new HaxeSourceRootModel(this, sourceRoot));
    }
    return out;
  }

  @NotNull
  public HaxeSourceRootModel getSdkRoot() {
    return sdkRoot;
  }

  @NotNull
  public HaxePackageModel getStdPackage() {
    return stdPackage;
  }

  @Nullable
  public List<HaxeModel> resolve(FullyQualifiedInfo info) {
    return resolve(info, null);
  }

  @Nullable
  public List<HaxeModel> resolve(FullyQualifiedInfo info, @Nullable GlobalSearchScope searchScope) {
    if (info == null) return null;
    HaxeModel resolvedValue;
    List<HaxeModel> result = new ArrayList<>();
    for (HaxeSourceRootModel root : getRoots()) {
      if (searchScope != null && !searchScope.contains(root.root))
        continue;
      resolvedValue = root.resolve(info);
      if (resolvedValue != null) result.add(resolvedValue);
    }

    if (result.isEmpty() && stdPackage != null) {
      resolvedValue = stdPackage.resolve(info);
      if (resolvedValue != null) result.add(resolvedValue);
    }

    return result;
  }

  @Nullable
  public HaxePackageModel resolvePackage(FullyQualifiedInfo info) {
    return resolvePackage(info, null);
  }

  @Nullable
  public HaxePackageModel resolvePackage(FullyQualifiedInfo info, @Nullable GlobalSearchScope scope) {
    List<HaxeModel> result = resolve(new FullyQualifiedInfo(info.packagePath, null, null, null), scope);
    if (result != null && !result.isEmpty() && result.get(0) instanceof HaxePackageModel) {
      return (HaxePackageModel)result.get(0);
    }
    return null;
  }

  public HaxeSourceRootModel getContainingRoot(PsiDirectory parent) {
    if (parent == null) return null;

    for (HaxeSourceRootModel root : getRoots()) {
      if (root.contains(parent)) {
        return root;
      }
    }
    return null;
  }
}