package com.intellij.plugins.haxe.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.plugins.haxe.ide.HaxeLookupElement;
import com.intellij.plugins.haxe.ide.refactoring.move.HaxeFileMoveHandler;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.util.HaxeAddImportHelper;
import com.intellij.plugins.haxe.util.HaxeElementGenerator;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.infos.CandidateInfo;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public abstract class HaxeReferenceImpl extends HaxeExpressionImpl implements HaxeReference, PsiPolyVariantReference {

  public HaxeReferenceImpl(ASTNode node) {
    super(node);
  }

  @Override
  public PsiElement getElement() {
    return this;
  }

  @Override
  public PsiReference getReference() {
    return this;
  }

  @Override
  public TextRange getRangeInElement() {
    final TextRange textRange = getTextRange();
    return new TextRange(0, textRange.getEndOffset() - textRange.getStartOffset());
  }

  @NotNull
  @Override
  public String getCanonicalText() {
    return getText();
  }

  @Override
  public boolean isSoft() {
    return false;
  }

  @Override
  public PsiElement resolve() {
    final ResolveResult[] resolveResults = multiResolve(true);

    return resolveResults.length == 0 ||
           resolveResults.length > 1 ||
           !resolveResults[0].isValidResult() ? null : resolveResults[0].getElement();
  }

  @NotNull
  @Override
  public ResolveResult[] multiResolve(boolean incompleteCode) {
    final List<? extends PsiElement> elements =
      ResolveCache.getInstance(getProject()).resolveWithCaching(this, HaxeResolver.INSTANCE, true, incompleteCode);
    return toCandidateInfoArray(elements);
  }

  @NotNull
  @Override
  public HaxeClassResolveResult resolveHaxeClass() {
    if (this instanceof HaxeThisExpression) {
      return HaxeClassResolveResult.create(PsiTreeUtil.getParentOfType(this, HaxeClass.class));
    }
    if (this instanceof HaxeSuperExpression) {
      final HaxeClass haxeClass = PsiTreeUtil.getParentOfType(this, HaxeClass.class);
      assert haxeClass != null;
      if (haxeClass.getExtendsList().isEmpty()) {
        return HaxeClassResolveResult.create(null);
      }
      final HaxeExpression superExpression = haxeClass.getExtendsList().get(0).getExpression();
      final HaxeClassResolveResult superClassResolveResult = superExpression instanceof HaxeReference
                                                             ? ((HaxeReference)superExpression).resolveHaxeClass()
                                                             : HaxeClassResolveResult.create(null);
      superClassResolveResult.specializeByParameters(haxeClass.getExtendsList().get(0).getTypeParam());
      return superClassResolveResult;
    }
    if (this instanceof HaxeLiteralExpression) {
      final LeafPsiElement child = (LeafPsiElement)getFirstChild();
      final IElementType childTokenType = child == null ? null : child.getElementType();
      return HaxeClassResolveResult.create(HaxeResolveUtil.findClassByQName(getLiteralClassName(childTokenType), this));
    }
    if (this instanceof HaxeArrayLiteral) {
      return HaxeClassResolveResult.create(HaxeResolveUtil.findClassByQName(getLiteralClassName(getTokenType()), this));
    }
    if (this instanceof HaxeNewExpression) {
      final HaxeClassResolveResult result = HaxeClassResolveResult.create(HaxeResolveUtil.tryResolveClassByQName(
        ((HaxeNewExpression)this).getType()));
      result.specialize(this);
      return result;
    }
    if (this instanceof HaxeCallExpression) {
      final HaxeExpression expression = ((HaxeCallExpression)this).getExpression();
      final HaxeClassResolveResult leftResult = tryGetLeftResolveResult(expression);
      if (expression instanceof HaxeReference) {
        final HaxeClassResolveResult result =
          HaxeResolveUtil.getHaxeClassResolveResult(((HaxeReference)expression).resolve(), leftResult.getSpecialization());
        result.specialize(this);
        return result;
      }
    }
    if (this instanceof HaxeArrayAccessExpression) {
      // wrong generation. see HaxeCallExpression
      final HaxeReference reference = PsiTreeUtil.getChildOfType(this, HaxeReference.class);
      if (reference != null) {
        final HaxeClassResolveResult resolveResult = reference.resolveHaxeClass();
        final HaxeClass resolveResultHaxeClass = resolveResult.getHaxeClass();
        if (resolveResultHaxeClass == null) {
          return resolveResult;
        }
        // std Array
        if ("Array".equalsIgnoreCase(resolveResultHaxeClass.getQualifiedName())) {
          return resolveResult.getSpecialization().get(resolveResultHaxeClass, "T");
        }
        // __get method
        return HaxeResolveUtil.getHaxeClassResolveResult(resolveResultHaxeClass.findMethodByName("__get"),
                                                         resolveResult.getSpecialization());
      }
    }
    HaxeClassResolveResult result = HaxeResolveUtil.getHaxeClassResolveResult(resolve(), tryGetLeftResolveResult(this).getSpecialization());
    if (result.getHaxeClass() == null) {
      result = HaxeClassResolveResult.create(HaxeResolveUtil.findClassByQName(getText(), this));
    }
    return result;
  }

  @NotNull
  private static HaxeClassResolveResult tryGetLeftResolveResult(HaxeExpression expression) {
    final HaxeReference[] childReferences = PsiTreeUtil.getChildrenOfType(expression, HaxeReference.class);
    final HaxeReference leftReference = childReferences != null ? childReferences[0] : null;
    return leftReference != null
           ? leftReference.resolveHaxeClass()
           : HaxeClassResolveResult.create(PsiTreeUtil.getParentOfType(expression, HaxeClass.class));
  }

  @Nullable
  private static String getLiteralClassName(IElementType type) {
    if (type == HaxeTokenTypes.LITSTRING || type == HaxeTokenTypes.LITCHAR) {
      return "String";
    }
    else if (type == HaxeTokenTypes.HAXE_ARRAY_LITERAL) {
      return "Array";
    }
    else if (type == HaxeTokenTypes.LITFLOAT) {
      return "Float";
    }
    else if (type == HaxeTokenTypes.REG_EXP) {
      return "EReg";
    }
    else if (type == HaxeTokenTypes.LITHEX || type == HaxeTokenTypes.LITINT || type == HaxeTokenTypes.LITOCT) {
      return "Int";
    }
    return null;
  }

  private static ResolveResult[] resolveByClassAndSymbol(@Nullable HaxeClassResolveResult resolveResult, @NotNull String symbolName) {
    return resolveResult == null ? ResolveResult.EMPTY_ARRAY : resolveByClassAndSymbol(resolveResult.getHaxeClass(), symbolName);
  }

  private static ResolveResult[] resolveByClassAndSymbol(@Nullable HaxeClass referenceClass, @NotNull String symbolName) {
    final HaxeNamedComponent namedSubComponent =
      HaxeResolveUtil.findNamedSubComponent(referenceClass, symbolName);
    final HaxeComponentName componentName = namedSubComponent == null ? null : namedSubComponent.getComponentName();
    return toCandidateInfoArray(componentName);
  }

  @NotNull
  private static ResolveResult[] toCandidateInfoArray(@Nullable PsiElement element) {
    if (element == null) {
      return ResolveResult.EMPTY_ARRAY;
    }
    return new ResolveResult[]{new CandidateInfo(element, null)};
  }

  @NotNull
  private static ResolveResult[] toCandidateInfoArray(List<? extends PsiElement> elements) {
    final ResolveResult[] result = new ResolveResult[elements.size()];
    for (int i = 0, size = elements.size(); i < size; i++) {
      result[i] = new CandidateInfo(elements.get(i), null);
    }
    return result;
  }

  @Override
  public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
    PsiElement element = this;
    if (getText().indexOf('.') != -1) {
      // qName
      final PsiElement lastChild = getLastChild();
      element = lastChild == null ? this : lastChild;
    }
    final HaxeIdentifier identifier = PsiTreeUtil.getChildOfType(element, HaxeIdentifier.class);
    final HaxeIdentifier identifierNew = HaxeElementGenerator.createIdentifierFromText(getProject(), newElementName);
    if (identifier != null && identifierNew != null) {
      element.getNode().replaceChild(identifier.getNode(), identifierNew.getNode());
    }
    return this;
  }

  @Override
  public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
    if (element instanceof HaxeFile) {
      bindToFile(element);
    }
    else if (element instanceof PsiPackage) {
      bindToPackage((PsiPackage)element);
    }
    return this;
  }

  private void bindToPackage(PsiPackage element) {
    final HaxeImportStatement importStatement =
      HaxeElementGenerator.createImportStatementFromPath(getProject(), element.getQualifiedName());
    assert importStatement != null;
    replace(importStatement.getExpression());
  }

  private void bindToFile(PsiElement element) {
    String destinationPackage = element.getUserData(HaxeFileMoveHandler.destinationPackageKey);
    if (destinationPackage == null) {
      destinationPackage = "";
    }
    final String importPath = (destinationPackage.isEmpty() ? "" : destinationPackage + ".") +
                              FileUtil.getNameWithoutExtension(((HaxeFile)element).getName());

    if (resolve() == null) {
      if (getParent() instanceof HaxeImportStatement && !destinationPackage.isEmpty()) {
        final HaxeImportStatement importStatement =
          HaxeElementGenerator.createImportStatementFromPath(getProject(), importPath);
        assert importStatement != null;
        getParent().replace(importStatement);
      }
      else if (getParent() instanceof HaxeImportStatement && destinationPackage.isEmpty()) {
        // need remove, empty destination
        getParent().getParent().deleteChildRange(getParent(), getParent());
      }
      else if (getText().indexOf('.') != -1) {
        // qName
        final String newQName = destinationPackage.equals(HaxeResolveUtil.getPackageName(getContainingFile())) ?
                                FileUtil.getNameWithoutExtension(((HaxeFile)element).getName()) :
                                importPath;
        final HaxeImportStatement importStatement =
          HaxeElementGenerator.createImportStatementFromPath(getProject(), newQName);
        assert importStatement != null;
        replace(importStatement.getExpression());
      }
      else if (UsefulPsiTreeUtil.findImportByClass(this, getText()) == null && !destinationPackage.isEmpty()) {
        // need add import
        HaxeAddImportHelper.addImport(importPath, getContainingFile());
      }
    }
    else {
      final HaxeImportStatement importStatement = UsefulPsiTreeUtil.findImportByClass(this, getText());
      if (importStatement != null && !importPath.equals(importStatement.getExpression().getText())) {
        // need remove, cause can resolve without
        importStatement.getParent().deleteChildRange(importStatement, importStatement);
      }
    }
  }

  @Override
  public boolean isReferenceTo(PsiElement element) {
    final PsiElement resolve = resolve();
    if (element instanceof HaxeFile &&
        resolve instanceof HaxeComponentName &&
        resolve.getParent() instanceof HaxeClass) {
      return element == resolve.getContainingFile();
    }
    final HaxeReference[] references = PsiTreeUtil.getChildrenOfType(this, HaxeReference.class);
    final boolean chain = references != null && references.length == 2;
    return !chain && resolve == element;
  }

  @NotNull
  @Override
  public Object[] getVariants() {
    final Set<HaxeComponentName> suggestedVariants = new THashSet<HaxeComponentName>();

    // if not first in chain
    // foo.bar.baz
    final HaxeReference referenceExpression = HaxeResolveUtil.getLeftReference(this);
    if (referenceExpression != null && getParent() instanceof HaxeReference && !referenceExpression.resolveHaxeClass().isFunctionType()) {
      addClassVariants(suggestedVariants, referenceExpression.resolveHaxeClass().getHaxeClass(),
                       !(referenceExpression instanceof HaxeThisExpression));
      addUsingVariants(suggestedVariants, referenceExpression.resolveHaxeClass().getHaxeClass(),
                       HaxeResolveUtil.findUsingClasses(getContainingFile()));
    }
    else {
      // if chain
      // node(foo.node(bar)).node(baz)
      final HaxeReference[] childReferences = PsiTreeUtil.getChildrenOfType(this, HaxeReference.class);
      final boolean isChain = childReferences != null && childReferences.length == 2;
      if (!isChain) {
        PsiTreeUtil.treeWalkUp(new ComponentNameScopeProcessor(suggestedVariants), this, null, new ResolveState());
        addClassVariants(suggestedVariants, PsiTreeUtil.getParentOfType(this, HaxeClass.class), false);
      }
    }

    return HaxeLookupElement.convert(suggestedVariants).toArray();
  }

  private static void addUsingVariants(Set<HaxeComponentName> variants, @Nullable HaxeClass ourClass, List<HaxeClass> classes) {
    for (HaxeClass haxeClass : classes) {
      for (HaxeNamedComponent haxeNamedComponent : HaxeResolveUtil.findNamedSubComponents(haxeClass)) {
        if (haxeNamedComponent.isPublic() && haxeNamedComponent.isStatic() && haxeNamedComponent.getComponentName() != null) {
          final HaxeClassResolveResult resolveResult = HaxeResolveUtil.findFirstParameterClass(haxeNamedComponent);
          final boolean needToAdd = resolveResult.getHaxeClass() == null || resolveResult.getHaxeClass() == ourClass;
          if (needToAdd) {
            variants.add(haxeNamedComponent.getComponentName());
          }
        }
      }
    }
  }

  private static void addClassVariants(Set<HaxeComponentName> suggestedVariants, @Nullable HaxeClass haxeClass, boolean filterByAccess) {
    if (haxeClass == null) {
      return;
    }
    for (HaxeNamedComponent namedComponent : HaxeResolveUtil.findNamedSubComponents(haxeClass)) {
      final boolean needFilter = filterByAccess && !namedComponent.isPublic();
      if (!needFilter && namedComponent.getComponentName() != null) {
        suggestedVariants.add(namedComponent.getComponentName());
      }
    }
  }
}
