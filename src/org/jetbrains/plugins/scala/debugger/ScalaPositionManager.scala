package org.jetbrains.plugins.scala
package debugger

import java.util
import java.util.Collections

import com.intellij.debugger.engine._
import com.intellij.debugger.jdi.VirtualMachineProxyImpl
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.debugger.{MultiRequestPositionManager, NoDataException, PositionManager, SourcePosition}
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.roots.impl.DirectoryIndex
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi._
import com.intellij.psi.search.{FilenameIndex, GlobalSearchScope}
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.psi.util.{CachedValueProvider, CachedValuesManager, PsiTreeUtil}
import com.intellij.util.{Processor, Query}
import com.sun.jdi._
import com.sun.jdi.request.ClassPrepareRequest
import org.jetbrains.annotations.{NotNull, Nullable}
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.jetbrains.plugins.scala.debugger.ScalaPositionManager._
import org.jetbrains.plugins.scala.debugger.evaluation.ScalaEvaluatorBuilderUtil
import org.jetbrains.plugins.scala.debugger.evaluation.evaluator.ScalaCompilingEvaluator
import org.jetbrains.plugins.scala.debugger.evaluation.util.DebuggerUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScBindingPattern, ScConstructorPattern, ScInfixPattern}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScParameter, ScParameters}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager
import org.jetbrains.plugins.scala.lang.psi.types.ValueClassType
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.util.macroDebug.ScalaMacroDebuggingUtil

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.NameTransformer
import scala.util.Try

/**
  * @author ilyas
  */
class ScalaPositionManager(val debugProcess: DebugProcess) extends PositionManager with MultiRequestPositionManager with LocationLineManager {

  protected[debugger] val caches = new ScalaPositionManagerCaches(debugProcess)
  import caches._

  ScalaPositionManager.cacheInstance(this)

  @Nullable
  def getSourcePosition(@Nullable location: Location): SourcePosition = {
    if (shouldSkip(location)) return null

    val position =
      for {
        loc <- location.toOption
        psiFile <- getPsiFileByReferenceType(debugProcess.getProject, loc.declaringType).toOption
        lineNumber = exactLineNumber(location)
        if lineNumber >= 0
      } yield {
        calcPosition(psiFile, location, lineNumber).getOrElse {
          SourcePosition.createFromLine(psiFile, lineNumber)
        }
      }
    position match {
      case Some(p) => p
      case None => throw NoDataException.INSTANCE
    }
  }

  @NotNull
  def getAllClasses(@NotNull position: SourcePosition): util.List[ReferenceType] = {

    val file = position.getFile
    throwIfNotScalaFile(file)

    val generatedClassName = file.getUserData(ScalaCompilingEvaluator.classNameKey)

    def hasLocations(refType: ReferenceType, position: SourcePosition): Boolean = {
      try {
        val generated = generatedClassName != null && refType.name().contains(generatedClassName)
        lazy val sameFile = getPsiFileByReferenceType(file.getProject, refType) == file

        generated || sameFile && locationsOfLine(refType, position).size > 0
      } catch {
        case _: NoDataException | _: AbsentInformationException | _: ClassNotPreparedException | _: ObjectCollectedException => false
      }
    }

    val possiblePositions = positionsOnLine(file, position.getLine)

    val exactClasses = ArrayBuffer[ReferenceType]()
    val namePatterns = mutable.Set[NamePattern]()
    inReadAction {
      val onTheLine = possiblePositions.map(findGeneratingClassOrMethodParent)
      if (onTheLine.isEmpty) return Collections.emptyList()
      val nonLambdaParent =
        if (isCompiledWithIndyLambdas(file)) {
          val nonStrictParents = onTheLine.head.withParentsInFile
          nonStrictParents.find(p => ScalaEvaluatorBuilderUtil.isGenerateNonAnonfunClass(p))
        } else None

      def addExactClasses(name: String) = {
        exactClasses ++= debugProcess.getVirtualMachineProxy.classesByName(name).asScala
      }

      val sourceImages = onTheLine ++ nonLambdaParent
      sourceImages.foreach {
        case null =>
        case tr: ScTrait if !DebuggerUtil.isLocalClass(tr) =>
          val traitImplName = getSpecificNameForDebugger(tr)
          val simpleName = traitImplName.stripSuffix("$class")
          Seq(simpleName, traitImplName).foreach(addExactClasses)
        case td: ScTypeDefinition if !DebuggerUtil.isLocalClass(td) =>
          val qName = getSpecificNameForDebugger(td)
          val delayedBodyName = if (isDelayedInit(td)) Seq(s"$qName$delayedInitBody") else Nil
          (qName +: delayedBodyName).foreach(addExactClasses)
        case elem =>
          val namePattern = NamePattern.forElement(elem)
          namePatterns ++= Option(namePattern)
      }
    }
    val packageName: Option[String] = Option(inReadAction(file.asInstanceOf[ScalaFile].getPackageName))

    val foundWithPattern =
      if (namePatterns.isEmpty) Nil
      else filterAllClasses(c => hasLocations(c, position) && namePatterns.exists(_.matches(c)), packageName)

    (exactClasses ++ foundWithPattern).distinct.asJava
  }

  @NotNull
  def locationsOfLine(@NotNull refType: ReferenceType, @NotNull position: SourcePosition): util.List[Location] = {

    throwIfNotScalaFile(position.getFile)
    checkForIndyLambdas(refType)

    try {
      val line: Int = position.getLine
      locationsOfLine(refType, line).asJava
    }
    catch {
      case e: AbsentInformationException => Collections.emptyList()
    }
  }

  def createPrepareRequest(@NotNull requestor: ClassPrepareRequestor, @NotNull position: SourcePosition): ClassPrepareRequest = {
    throw new IllegalStateException("This class implements MultiRequestPositionManager, corresponding createPrepareRequests version should be used")
  }

  override def createPrepareRequests(requestor: ClassPrepareRequestor, position: SourcePosition): util.List[ClassPrepareRequest] = {
    def isLocalOrUnderDelayedInit(definition: PsiClass): Boolean = {
      DebuggerUtil.isLocalClass(definition) || isDelayedInit(definition)
    }

    def findEnclosingTypeDefinition: Option[ScTypeDefinition] = {
      @tailrec
      def notLocalEnclosingTypeDefinition(element: PsiElement): Option[ScTypeDefinition] = {
        PsiTreeUtil.getParentOfType(element, classOf[ScTypeDefinition]) match {
          case null => None
          case td if DebuggerUtil.isLocalClass(td) => notLocalEnclosingTypeDefinition(td.getParent)
          case td => Some(td)
        }
      }
      val element = nonWhitespaceElement(position)
      notLocalEnclosingTypeDefinition(element)
    }

    def createPrepareRequest(position: SourcePosition): ClassPrepareRequest = {
      val qName = new Ref[String](null)
      val waitRequestor = new Ref[ClassPrepareRequestor](null)
      inReadAction {
        val sourceImage = findReferenceTypeSourceImage(position)
        val insideMacro: Boolean = isInsideMacro(nonWhitespaceElement(position))
        sourceImage match {
          case cl: ScClass if ValueClassType.isValueClass(cl) =>
            //there are no instances of value classes, methods from companion object are used
            qName.set(getSpecificNameForDebugger(cl) + "$")
          case tr: ScTrait if !DebuggerUtil.isLocalClass(tr) =>
            //to handle both trait methods encoding
            qName.set(tr.getQualifiedNameForDebugger + "*")
          case typeDef: ScTypeDefinition if !isLocalOrUnderDelayedInit(typeDef) =>
            val specificName = getSpecificNameForDebugger(typeDef)
            qName.set(if (insideMacro) specificName + "*" else specificName)
          case _ =>
            findEnclosingTypeDefinition.foreach(typeDef => qName.set(typeDef.getQualifiedNameForDebugger + "*"))
        }
        // Enclosing type definition is not found
        if (qName.get == null) {
          qName.set(SCRIPT_HOLDER_CLASS_NAME + "*")
        }
        waitRequestor.set(new ScalaPositionManager.MyClassPrepareRequestor(position, requestor))
      }

      debugProcess.getRequestsManager.createClassPrepareRequest(waitRequestor.get, qName.get)
    }

    val file = position.getFile
    throwIfNotScalaFile(file)

    val possiblePositions = inReadAction {
      positionsOnLine(file, position.getLine).map(SourcePosition.createFromElement)
    }
    possiblePositions.map(createPrepareRequest).asJava
  }

  private def throwIfNotScalaFile(file: PsiFile): Unit = {
    if (!checkScalaFile(file)) throw NoDataException.INSTANCE
  }

  private def checkScalaFile(file: PsiFile): Boolean = file match {
    case sf: ScalaFile => !sf.isCompiled
    case _ => false
  }

  private def filterAllClasses(condition: ReferenceType => Boolean, packageName: Option[String]): Seq[ReferenceType] = {
    def samePackage(refType: ReferenceType) = {
      val name = refType.name()
      val lastDot = name.lastIndexOf('.')
      val refTypePackageName = if (lastDot < 0) "" else name.substring(0, lastDot)
      packageName.isEmpty || packageName.contains(refTypePackageName)
    }

    def isAppropriate(refType: ReferenceType) = {
      Try(samePackage(refType) && refType.isInitialized && condition(refType)).getOrElse(false)
    }

    import scala.collection.JavaConverters._
    for {
      refType <- debugProcess.getVirtualMachineProxy.allClasses.asScala
      if isAppropriate(refType)
    } yield {
      refType
    }
  }

  @Nullable
  private def findReferenceTypeSourceImage(@NotNull position: SourcePosition): PsiElement = {
    val element = nonWhitespaceElement(position)
    findGeneratingClassOrMethodParent(element)
  }

  protected def nonWhitespaceElement(@NotNull position: SourcePosition): PsiElement = {
    val file = position.getFile
    @tailrec
    def nonWhitespaceInner(element: PsiElement, document: Document): PsiElement = {
      element match {
        case null => null
        case ws: PsiWhiteSpace if document.getLineNumber(element.getTextRange.getEndOffset) == position.getLine =>
          val nextElement = file.findElementAt(element.getTextRange.getEndOffset)
          nonWhitespaceInner(nextElement, document)
        case _ => element
      }
    }
    if (!file.isInstanceOf[ScalaFile]) null
    else {
      val firstElement = file.findElementAt(position.getOffset)
      try {
        val document = PsiDocumentManager.getInstance(file.getProject).getDocument(file)
        nonWhitespaceInner(firstElement, document)
      }
      catch {
        case t: Throwable => firstElement
      }
    }
  }

  private def calcPosition(file: PsiFile, location: Location, lineNumber: Int): Option[SourcePosition] = {
    throwIfNotScalaFile(file)

    def isDefaultArgument(method: Method) = {
      val methodName = method.name()
      val lastDollar = methodName.lastIndexOf("$")
      if (lastDollar >= 0) {
        val (start, index) = methodName.splitAt(lastDollar + 1)
        (start.endsWith("$default$"), index)
      }
      else (false, "")
    }

    def findDefaultArg(possiblePositions: Seq[PsiElement], defaultArgIndex: String) : Option[PsiElement] = {
      try {
        val paramNumber = defaultArgIndex.toInt - 1
        possiblePositions.find {
          case e =>
            val scParameters = PsiTreeUtil.getParentOfType(e, classOf[ScParameters])
            if (scParameters != null) {
              val param = scParameters.params(paramNumber)
              param.isDefaultParam && param.isAncestorOf(e)
            }
            else false
        }
      } catch {
        case e: Exception => None
      }
    }

    def calcElement(): Option[PsiElement] = {
      val possiblePositions = positionsOnLine(file, lineNumber)
      val currentMethod = location.method()

      lazy val (isDefaultArg, defaultArgIndex) = isDefaultArgument(currentMethod)

      def findPsiElementForIndyLambda(): Option[PsiElement] = {
        val lambdas = lambdasOnLine(file, lineNumber)
        val methods = indyLambdaMethodsOnLine(location.declaringType(), lineNumber)
        val methodsToLambdas = methods.zip(lambdas).toMap
        methodsToLambdas.get(currentMethod)
      }

      if (possiblePositions.size <= 1) {
        possiblePositions.headOption
      }
      else if (isIndyLambda(currentMethod)) {
        findPsiElementForIndyLambda()
      }
      else if (isDefaultArg) {
        findDefaultArg(possiblePositions, defaultArgIndex)
      }
      else if (!isAnonfun(currentMethod)) {
        possiblePositions.find {
          case e: PsiElement if isLambda(e) => false
          case (e: ScExpression) childOf (p: ScParameter) => false
          case _ => true
        }
      }
      else {
        val generatingPsiElem = findElementByReferenceType(location.declaringType())
        possiblePositions.find(p => generatingPsiElem.contains(findGeneratingClassOrMethodParent(p)))
      }
    }

    calcElement().map(SourcePosition.createFromElement)
  }

  private def findScriptFile(refType: ReferenceType): Option[PsiFile] = {
    try {
      val name = refType.name()
      if (name.startsWith(SCRIPT_HOLDER_CLASS_NAME)) {
        cachedSourceName(refType) match {
          case Some(srcName) =>
            val files = FilenameIndex.getFilesByName(debugProcess.getProject, srcName, debugProcess.getSearchScope)
            files.headOption
          case _ => None
        }
      }
      else None
    }
    catch {
      case e: AbsentInformationException => None
    }
  }

  @Nullable
  private def getPsiFileByReferenceType(project: Project, refType: ReferenceType): PsiFile = {
    if (refType == null) return null
    if (refTypeToFileCache.contains(refType)) return refTypeToFileCache(refType)

    def searchForMacroDebugging(qName: String): PsiFile = {
      val directoryIndex: DirectoryIndex = DirectoryIndex.getInstance(project)
      val dotIndex = qName.lastIndexOf(".")
      val packageName = if (dotIndex > 0) qName.substring(0, dotIndex) else ""
      val query: Query[VirtualFile] = directoryIndex.getDirectoriesByPackageName(packageName, true)
      val fileNameWithoutExtension = if (dotIndex > 0) qName.substring(dotIndex + 1) else qName
      val fileNames: util.Set[String] = new util.HashSet[String]
      import scala.collection.JavaConversions._
      for (extention <- ScalaLoader.SCALA_EXTENSIONS) {
        fileNames.add(fileNameWithoutExtension + "." + extention)
      }
      val result = new Ref[PsiFile]
      query.forEach(new Processor[VirtualFile] {
        override def process(vDir: VirtualFile): Boolean = {
          var isFound = false
          for {
            fileName <- fileNames
            if !isFound
            vFile <- vDir.findChild(fileName).toOption
          } {
            val psiFile: PsiFile = PsiManager.getInstance(project).findFile(vFile)
            val debugFile: PsiFile = ScalaMacroDebuggingUtil.loadCode(psiFile, force = false)
            if (debugFile != null) {
              result.set(debugFile)
              isFound = true
            }
            else if (psiFile.isInstanceOf[ScalaFile]) {
              result.set(psiFile)
              isFound = true
            }
          }
          !isFound
        }
      })
      result.get
    }

    def findFile() = {
      def withDollarTestName(originalQName: String): Option[String] = {
        val dollarTestSuffix = "$Test" //See SCL-9340
        if (originalQName.endsWith(dollarTestSuffix)) Some(originalQName)
        else if (originalQName.contains(dollarTestSuffix + "$")) {
          val index = originalQName.indexOf(dollarTestSuffix) + dollarTestSuffix.length
          Some(originalQName.take(index))
        }
        else None
      }
      def topLevelClassName(originalQName: String): String = {
        if (originalQName.endsWith(packageSuffix)) originalQName
        else originalQName.replace(packageSuffix, ".").takeWhile(_ != '$')
      }
      def tryToFindClass(name: String) = {
        findClassByQualName(name, isScalaObject = false)
          .orElse(findClassByQualName(name, isScalaObject = true))
      }

      val scriptFile = findScriptFile(refType)
      val file = scriptFile.getOrElse {
        val originalQName = NameTransformer.decode(refType.name)

        if (!ScalaMacroDebuggingUtil.isEnabled) {
          val clazz = withDollarTestName(originalQName).flatMap(tryToFindClass)
            .orElse(tryToFindClass(topLevelClassName(originalQName)))
          clazz.map(_.getNavigationElement.getContainingFile).orNull
        }
        else
          searchForMacroDebugging(topLevelClassName(originalQName))
      }
      file
    }

    val file = inReadAction(findFile())
    if (file != null && refType.methods().asScala.exists(isIndyLambda)) {
      isCompiledWithIndyLambdasCache.put(file, true)
    }
    refTypeToFileCache.put(refType, file)
    file
  }

  private def nameMatches(elem: PsiElement, refType: ReferenceType): Boolean = {
    val pattern = NamePattern.forElement(elem)
    pattern != null && pattern.matches(refType)
  }

  private def checkForIndyLambdas(refType: ReferenceType) = {
    if (!refTypeToFileCache.contains(refType)) {
      getPsiFileByReferenceType(debugProcess.getProject, refType)
    }
  }

  def findElementByReferenceType(refType: ReferenceType): Option[PsiElement] = {
    def createPointer(elem: PsiElement) =
      SmartPointerManager.getInstance(debugProcess.getProject).createSmartPsiElementPointer(elem)

    refTypeToElementCache.get(refType) match {
      case Some(Some(p)) if p.getElement != null => Some(p.getElement)
      case Some(Some(_)) | None =>
        val found = findElementByReferenceTypeInner(refType)
        refTypeToElementCache.update(refType, found.map(createPointer))
        found
      case Some(None) => None
    }
  }

  private def findElementByReferenceTypeInner(refType: ReferenceType): Option[PsiElement] = {

    val byName = findByQualName(refType) orElse findByShortName(refType)
    if (byName.isDefined) return byName

    val project = debugProcess.getProject

    val allLocations = Try(refType.allLineLocations().asScala).getOrElse(Seq.empty)

    val refTypeLineNumbers = allLocations.map(checkedLineNumber).filter(_ > 0)
    if (refTypeLineNumbers.isEmpty) return None

    val firstRefTypeLine = refTypeLineNumbers.min
    val lastRefTypeLine = refTypeLineNumbers.max
    val refTypeLines = firstRefTypeLine to lastRefTypeLine

    val file = getPsiFileByReferenceType(project, refType)
    if (!checkScalaFile(file)) return None

    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    if (document == null) return None

    def elementLineRange(elem: PsiElement, document: Document) = {
      val startLine = document.getLineNumber(elem.getTextRange.getStartOffset)
      val endLine = document.getLineNumber(elem.getTextRange.getEndOffset)
      startLine to endLine
    }

    def checkLines(elem: PsiElement, document: Document) = {
      val lineRange = elementLineRange(elem, document)
      //intersection, very loose check because sometimes first line for <init> method is after range of the class
      firstRefTypeLine <= lineRange.end && lastRefTypeLine >= lineRange.start
    }

    def isAppropriateCandidate(elem: PsiElement) = {
      checkLines(elem, document) && ScalaEvaluatorBuilderUtil.isGenerateClass(elem) && nameMatches(elem, refType)
    }

    def findCandidates(): Seq[PsiElement] = {
      def findAt(offset: Int): Option[PsiElement] = {
        val startElem = file.findElementAt(offset)
        startElem.parentsInFile.find(isAppropriateCandidate)
      }
      if (lastRefTypeLine - firstRefTypeLine >= 2) {
        val offsetsInTheMiddle = Seq(
          document.getLineEndOffset(firstRefTypeLine),
          document.getLineEndOffset(firstRefTypeLine + 1)
        )
        offsetsInTheMiddle.flatMap(findAt).distinct
      }
      else {
        val firstLinePositions = positionsOnLine(file, firstRefTypeLine)
        val allPositions =
          if (firstRefTypeLine == lastRefTypeLine) firstLinePositions
          else firstLinePositions ++ positionsOnLine(file, lastRefTypeLine)
        allPositions.distinct.filter(isAppropriateCandidate)
      }
    }

    def filterWithSignature(candidates: Seq[PsiElement]) = {
      val applySignature = refType.methodsByName("apply").asScala.find(m => !m.isSynthetic).map(_.signature())
      if (applySignature.isEmpty) candidates
      else {
        candidates.filter(l => applySignature == DebuggerUtil.lambdaJVMSignature(l))
      }
    }

    val candidates = findCandidates()

    if (candidates.size <= 1) return candidates.headOption

    if (refTypeLines.size > 1) {
      val withExactlySameLines = candidates.filter(elementLineRange(_, document) == refTypeLines)
      if (withExactlySameLines.size == 1) return withExactlySameLines.headOption
    }

    if (candidates.exists(!isLambda(_))) return candidates.headOption

    val filteredWithSignature = filterWithSignature(candidates)
    if (filteredWithSignature.size == 1) return filteredWithSignature.headOption

    val byContainingClasses = filteredWithSignature.groupBy(c => findGeneratingClassOrMethodParent(c.getParent))
    if (byContainingClasses.size > 1) {
      findContainingClass(refType) match {
        case Some(e) => return byContainingClasses.get(e).flatMap(_.headOption)
        case None =>
      }
    }
    filteredWithSignature.headOption
  }

  private def findClassByQualName(qName: String, isScalaObject: Boolean): Option[PsiClass] = {
    val project = debugProcess.getProject

    val cacheManager = ScalaShortNamesCacheManager.getInstance(project)
    val classes =
      if (qName.endsWith(packageSuffix))
        Option(cacheManager.getPackageObjectByName(qName.stripSuffix(packageSuffix), GlobalSearchScope.allScope(project))).toSeq
      else
        cacheManager.getClassesByFQName(qName.replace(packageSuffix, "."), debugProcess.getSearchScope)

    val clazz =
      if (classes.length == 1) classes.headOption
      else if (classes.length >= 2) {
        if (isScalaObject) classes.find(_.isInstanceOf[ScObject])
        else classes.find(!_.isInstanceOf[ScObject])
      }
      else None
    clazz.filter(_.isValid)
  }

  private def findByQualName(refType: ReferenceType): Option[PsiClass] = {
    val originalQName = NameTransformer.decode(refType.name)
    val endsWithPackageSuffix = originalQName.endsWith(packageSuffix)
    val withoutSuffix =
      if (endsWithPackageSuffix) originalQName.stripSuffix(packageSuffix)
      else originalQName.stripSuffix("$").stripSuffix("$class")
    val withDots = withoutSuffix.replace(packageSuffix, ".").replace('$', '.')
    val transformed = if (endsWithPackageSuffix) withDots + packageSuffix else withDots

    findClassByQualName(transformed, originalQName.endsWith("$"))
  }

  private def findByShortName(refType: ReferenceType): Option[PsiClass] = {
    val project = debugProcess.getProject

    if (DumbService.getInstance(project).isDumb) return None

    lazy val sourceName = cachedSourceName(refType).getOrElse("")

    def sameFileName(elem: PsiElement) = {
      val containingFile = elem.getContainingFile
      containingFile != null && containingFile.name == sourceName
    }

    val originalQName = NameTransformer.decode(refType.name)
    val withoutSuffix =
      if (originalQName.endsWith(packageSuffix)) originalQName
      else originalQName.replace(packageSuffix, ".").stripSuffix("$").stripSuffix("$class")
    val lastDollar = withoutSuffix.lastIndexOf('$')
    val lastDot = withoutSuffix.lastIndexOf('.')
    val index = Seq(lastDollar, lastDot, 0).max + 1
    val name = withoutSuffix.drop(index)
    val isScalaObject = originalQName.endsWith("$")

    val cacheManager = ScalaShortNamesCacheManager.getInstance(project)
    val classes = cacheManager.getClassesByName(name, GlobalSearchScope.allScope(project))

    val inSameFile = classes.filter(c => c.isValid && sameFileName(c))

    if (inSameFile.length == 1) classes.headOption
    else if (inSameFile.length >= 2) {
      if (isScalaObject) inSameFile.find(_.isInstanceOf[ScObject])
      else inSameFile.find(!_.isInstanceOf[ScObject])
    }
    else None
  }

  private def findContainingClass(refType: ReferenceType): Option[PsiElement] = {
    def classesByName(s: String) = {
      val vm = debugProcess.getVirtualMachineProxy
      vm.classesByName(s).asScala
    }

    val name = NameTransformer.decode(refType.name())
    val index = name.lastIndexOf("$$")
    if (index < 0) return None

    val containingName = NameTransformer.encode(name.substring(0, index))
    classesByName(containingName).headOption.flatMap(findElementByReferenceType)
  }
}

object ScalaPositionManager {
  private val SCRIPT_HOLDER_CLASS_NAME: String = "Main$$anon$1"
  private val packageSuffix = ".package$"
  private val delayedInitBody = "delayedInit$body"

  private val isCompiledWithIndyLambdasCache = mutable.HashMap[PsiFile, Boolean]()

  private val instances = mutable.HashMap[DebugProcess, ScalaPositionManager]()
  private def cacheInstance(scPosManager: ScalaPositionManager) = {
    val debugProcess = scPosManager.debugProcess

    instances.put(debugProcess, scPosManager)
    debugProcess.addDebugProcessListener(new DebugProcessAdapter {
      override def processDetached(process: DebugProcess, closedByUser: Boolean): Unit = {
        ScalaPositionManager.instances.remove(process)
        debugProcess.removeDebugProcessListener(this)
      }
    })
  }

  def instance(vm: VirtualMachine): Option[ScalaPositionManager] = instances.collectFirst {
    case (process, manager) if getVM(process).contains(vm) => manager
  }

  def instance(debugProcess: DebugProcess): Option[ScalaPositionManager] = instances.get(debugProcess)

  def instance(mirror: Mirror): Option[ScalaPositionManager] = instance(mirror.virtualMachine())

  private def getVM(debugProcess: DebugProcess) = {
    debugProcess.getVirtualMachineProxy match {
      case impl: VirtualMachineProxyImpl => Option(impl.getVirtualMachine)
      case _ => None
    }
  }

  def positionsOnLine(file: PsiFile, lineNumber: Int): Seq[PsiElement] = {
    if (lineNumber < 0) return Seq.empty

    val scFile = file match {
      case sf: ScalaFile => sf
      case _ => return Seq.empty
    }
    val cacheProvider = new CachedValueProvider[mutable.HashMap[Int, Seq[PsiElement]]] {
      override def compute(): Result[mutable.HashMap[Int, Seq[PsiElement]]] = Result.create(mutable.HashMap[Int, Seq[PsiElement]](), file)
    }

    CachedValuesManager.getCachedValue(file, cacheProvider).getOrElseUpdate(lineNumber, positionsOnLineInner(scFile, lineNumber))
  }

  def checkedLineNumber(location: Location): Int =
    try location.lineNumber() - 1
    catch {case ie: InternalError => -1}

  def cachedSourceName(refType: ReferenceType) = {
    ScalaPositionManager.instance(refType).map(_.caches).flatMap(_.cachedSourceName(refType))
  }

  private def positionsOnLineInner(file: ScalaFile, lineNumber: Int): Seq[PsiElement] = {
    inReadAction {
      val document = PsiDocumentManager.getInstance(file.getProject).getDocument(file)
      if (document == null || lineNumber >= document.getLineCount) return Seq.empty
      val startLine = document.getLineStartOffset(lineNumber)
      val endLine = document.getLineEndOffset(lineNumber)

      def elementsOnTheLine(file: ScalaFile, lineNumber: Int): Seq[PsiElement] = {
        val result = ArrayBuffer[PsiElement]()
        var elem = file.findElementAt(startLine)

        while (elem != null && elem.getTextOffset <= endLine) {
          elem match {
            case ChildOf(_: ScUnitExpr) | ChildOf(ScBlock()) =>
              result += elem
            case ElementType(t) if ScalaTokenTypes.WHITES_SPACES_AND_COMMENTS_TOKEN_SET.contains(t) ||
              ScalaTokenTypes.BRACES_TOKEN_SET.contains(t) =>
            case _ =>
              result += elem
          }
          elem = PsiTreeUtil.nextLeaf(elem, true)
        }
        result
      }

      def findParent(element: PsiElement): Option[PsiElement] = {
        val parentsOnTheLine = element.withParentsInFile.takeWhile(e => e.getTextOffset > startLine).toIndexedSeq
        val anon = parentsOnTheLine.collectFirst {
          case e if isLambda(e) => e
          case newTd: ScNewTemplateDefinition if DebuggerUtil.generatesAnonClass(newTd) => newTd
        }
        val filteredParents = parentsOnTheLine.reverse.filter {
          case _: ScExpression => true
          case _: ScConstructorPattern | _: ScInfixPattern | _: ScBindingPattern => true
          case callRefId childOf ((ref: ScReferenceExpression) childOf (_: ScMethodCall))
            if ref.nameId == callRefId && ref.getTextRange.getStartOffset < startLine => true
          case _: ScTypeDefinition => true
          case _ => false
        }
        val maxExpressionPatternOrTypeDef =
          filteredParents.find(!_.isInstanceOf[ScBlock]).orElse(filteredParents.headOption)
        Seq(anon, maxExpressionPatternOrTypeDef).flatten.sortBy(_.getTextLength).headOption
      }
      elementsOnTheLine(file, lineNumber).flatMap(findParent).distinct
    }
  }

  def isLambda(element: PsiElement) = {
    ScalaEvaluatorBuilderUtil.isGenerateAnonfun(element) && !isInsideMacro(element)
  }

  def lambdasOnLine(file: PsiFile, lineNumber: Int): Seq[PsiElement] = {
    positionsOnLine(file, lineNumber).filter(isLambda)
  }

  def isIndyLambda(m: Method): Boolean = {
    val name = m.name()
    val lastDollar = name.lastIndexOf('$')
    lastDollar > 0 && name.substring(0, lastDollar).endsWith("$anonfun")
  }

  def isAnonfunType(refType: ReferenceType) = {
    refType match {
      case ct: ClassType =>
        val supClass = ct.superclass()
        supClass != null && supClass.name().startsWith("scala.runtime.AbstractFunction")
      case _ => false
    }
  }

  def isAnonfun(m: Method): Boolean = {
    isIndyLambda(m) || m.name.startsWith("apply") && isAnonfunType(m.declaringType())
  }

  def indyLambdaMethodsOnLine(refType: ReferenceType, lineNumber: Int): Seq[Method] = {
    def ordinal(m: Method) = {
      val name = m.name()
      val lastDollar = name.lastIndexOf('$')
      Try(name.substring(lastDollar + 1).toInt).getOrElse(-1)
    }

    val all = refType.methods().asScala.filter(isIndyLambda)
    val onLine = all.filter(m => Try(!m.locationsOfLine(lineNumber + 1).isEmpty).getOrElse(false))
    onLine.sortBy(ordinal)
  }

  def isCompiledWithIndyLambdas(file: PsiFile): Boolean = {
    if (file == null) false
    else {
      val originalFile = Option(file.getUserData(ScalaCompilingEvaluator.originalFileKey)).getOrElse(file)
      isCompiledWithIndyLambdasCache.getOrElse(originalFile, false)
    }
  }

  @tailrec
  def findGeneratingClassOrMethodParent(element: PsiElement): PsiElement = {
    element match {
      case null => null
      case elem if ScalaEvaluatorBuilderUtil.isGenerateClass(elem) || isLambda(elem) => elem
      case InsideMacro(macroCall) => macroCall
      case elem => findGeneratingClassOrMethodParent(elem.getParent)
    }
  }

  private object MacroDef {
    val macroImpl = "scala.reflect.macros.internal.macroImpl"
    def unapply(fun: ScFunction): Option[ScFunction] = {
      fun match {
        case m: ScMacroDefinition => Some(m)
        case _ if fun.annotations.map(_.constructor.typeElement.getText).contains(macroImpl) => Some(fun)
        case _ => None
      }
    }
  }

  private object InsideMacro {
    def unapply(elem: PsiElement): Option[ScMethodCall] = {
      elem.parentsInFile.collectFirst {
        case mc @ ScMethodCall(ResolvesTo(MacroDef(_)), _) => mc
      }
    }
  }

  object InsideAsync {
    def unapply(elem: PsiElement): Option[ScMethodCall] = elem match {
      case InsideMacro(call @ ScMethodCall(ref: ScReferenceExpression, _)) if ref.refName == "async" => Some(call)
      case _ => None
    }
  }

  def isInsideMacro(elem: PsiElement): Boolean = InsideMacro.unapply(elem).isDefined

  def shouldSkip(location: Location, debugProcess: DebugProcess) = {
    ScalaPositionManager.instance(debugProcess).forall(_.shouldSkip(location))
  }

  private def getSpecificNameForDebugger(td: ScTypeDefinition): String = {
    val name = td.getQualifiedNameForDebugger

    td match {
      case _: ScObject => s"$name$$"
      case _: ScTrait => s"$name$$class"
      case _ => name
    }
  }

  def isDelayedInit(cl: PsiClass) = cl match {
    case obj: ScObject =>
      val manager: ScalaPsiManager = ScalaPsiManager.instance(obj.getProject)
      val clazz: PsiClass = manager.getCachedClass(obj.getResolveScope, "scala.DelayedInit").orNull
      clazz != null && manager.cachedDeepIsInheritor(obj, clazz)
    case _ => false
  }

  private class MyClassPrepareRequestor(position: SourcePosition, requestor: ClassPrepareRequestor) extends ClassPrepareRequestor {
    private val sourceFile = position.getFile
    private val sourceName = sourceFile.getName
    private def sourceNameOf(refType: ReferenceType): Option[String] = ScalaPositionManager.cachedSourceName(refType)

    def processClassPrepare(debuggerProcess: DebugProcess, referenceType: ReferenceType) {
      val positionManager: CompoundPositionManager = debuggerProcess.asInstanceOf[DebugProcessImpl].getPositionManager

      if (!sourceNameOf(referenceType).contains(sourceName)) return

      if (positionManager.locationsOfLine(referenceType, position).size > 0) {
        requestor.processClassPrepare(debuggerProcess, referenceType)
      }
      else {
        val positionClasses: util.List[ReferenceType] = positionManager.getAllClasses(position)
        if (positionClasses.contains(referenceType)) {
          requestor.processClassPrepare(debuggerProcess, referenceType)
        }
      }
    }
  }

  private class NamePattern(elem: PsiElement) {
    private val containingFile = elem.getContainingFile
    private val sourceName = containingFile.getName
    private val isGeneratedForCompilingEvaluator = containingFile.getUserData(ScalaCompilingEvaluator.classNameKey) != null
    private var compiledWithIndyLambdas = isCompiledWithIndyLambdas(containingFile)
    private val exactName: Option[String] = {
      elem match {
        case td: ScTypeDefinition if !DebuggerUtil.isLocalClass(td) =>
          Some(getSpecificNameForDebugger(td))
        case _ => None
      }
    }
    private var classJVMNameParts: Seq[String] = null

    private def computeClassJVMNameParts: Seq[String] = {
      if (exactName.isDefined) Seq.empty
      else inReadAction {
        val parts = elem.withParentsInFile.flatMap(partsFor)
        parts.toSeq.reverse
      }
    }

    private def partsFor(elem: PsiElement): Seq[String] = {
      elem match {
        case td: ScTypeDefinition => Seq(ScalaNamesUtil.toJavaName(td.name))
        case newTd: ScNewTemplateDefinition if DebuggerUtil.generatesAnonClass(newTd) => Seq("$anon")
        case e if ScalaEvaluatorBuilderUtil.isGenerateClass(e) => partsForAnonfun(e)
        case _ => Seq.empty
      }
    }

    private def partsForAnonfun(elem: PsiElement): Seq[String] = {
      val anonfunCount = ScalaEvaluatorBuilderUtil.anonClassCount(elem)
      val lastParts = Seq.fill(anonfunCount - 1)(Seq("$apply", "$anonfun")).flatten
      val containingClass = findGeneratingClassOrMethodParent(elem.getParent)
      val owner = PsiTreeUtil.getParentOfType(elem, classOf[ScFunctionDefinition], classOf[ScTypeDefinition],
        classOf[ScPatternDefinition], classOf[ScVariableDefinition])
      val firstParts =
        if (PsiTreeUtil.isAncestor(owner, containingClass, true)) Seq("$anonfun")
        else owner match {
          case fun: ScFunctionDefinition =>
            val name = if (fun.name == "this") JVMNameUtil.CONSTRUCTOR_NAME else fun.name
            val encoded = NameTransformer.encode(name)
            Seq(s"$$$encoded", "$anonfun")
          case _ => Seq("$anonfun")
        }
      lastParts ++ firstParts
    }

    private def checkParts(name: String): Boolean = {
      var nameTail = name
      updateParts()

      for (part <- classJVMNameParts) {
        val index = nameTail.indexOf(part)
        if (index >= 0) {
          nameTail = nameTail.substring(index + part.length)
        }
        else return false
      }
      nameTail.indexOf("$anon") == -1
    }

    def updateParts(): Unit = {
      val newValue = isCompiledWithIndyLambdas(containingFile)
      if (newValue != compiledWithIndyLambdas || classJVMNameParts == null) {
        compiledWithIndyLambdas = newValue
        classJVMNameParts = computeClassJVMNameParts
      }
    }

    def matches(refType: ReferenceType): Boolean = {
      val refTypeSourceName = cachedSourceName(refType).getOrElse("")
      if (refTypeSourceName != sourceName && !isGeneratedForCompilingEvaluator) return false

      val name = refType.name()

      exactName match {
        case Some(qName) => qName == name || qName.stripSuffix("$class") == name
        case None => checkParts(name)
      }
    }
  }

  private object NamePattern {
    def forElement(elem: PsiElement): NamePattern = {
      if (elem == null || !ScalaEvaluatorBuilderUtil.isGenerateClass(elem)) return null

      val cacheProvider = new CachedValueProvider[NamePattern] {
        override def compute(): Result[NamePattern] = Result.create(new NamePattern(elem), elem)
      }

      CachedValuesManager.getCachedValue(elem, cacheProvider)
    }
  }

  private[debugger] class ScalaPositionManagerCaches(debugProcess: DebugProcess) {

    debugProcess.addDebugProcessListener(new DebugProcessAdapter {
      override def processDetached(process: DebugProcess, closedByUser: Boolean): Unit = {
        clear()
        process.removeDebugProcessListener(this)
      }
    })

    val refTypeToFileCache = mutable.HashMap[ReferenceType, PsiFile]()
    val refTypeToElementCache = mutable.HashMap[ReferenceType, Option[SmartPsiElementPointer[PsiElement]]]()

    val customizedLocationsCache = mutable.HashMap[Location, Int]()
    val lineToCustomizedLocationCache = mutable.HashMap[(ReferenceType, Int), Seq[Location]]()
    val seenRefTypes = mutable.Set[ReferenceType]()
    val sourceNames = mutable.HashMap[ReferenceType, Option[String]]()

    def cachedSourceName(refType: ReferenceType): Option[String] =
      sourceNames.getOrElseUpdate(refType, Try(refType.sourceName()).toOption)

    def clear(): Unit = {
      isCompiledWithIndyLambdasCache.clear()

      refTypeToFileCache.clear()
      refTypeToElementCache.clear()

      customizedLocationsCache.clear()
      lineToCustomizedLocationCache.clear()
      seenRefTypes.clear()
      sourceNames.clear()
    }
  }
}