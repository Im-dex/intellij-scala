package org.jetbrains.plugins.scala.lang.psi.impl.base

import org.jetbrains.plugins.scala.lang._
import lexer.ScalaTokenTypes
import parser.ScalaElementTypes
import psi.ScalaPsiElementImpl
import psi.api.base._
import psi.types._
import psi.api.toplevel.typedef.ScTypeDefinition
import psi.api.base.types.ScSimpleTypeElement
import psi.impl.ScalaPsiElementFactory
import resolve._
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports._
import com.intellij.psi.tree.TokenSet
import com.intellij.lang.ASTNode
import com.intellij.psi.tree.IElementType
import com.intellij.psi._
import com.intellij.psi.impl._
import org.jetbrains.annotations._
import org.jetbrains.plugins.scala.icons.Icons
import com.intellij.psi.PsiElement
import com.intellij.openapi.util._
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.TextRange
import com.intellij.util.IncorrectOperationException
import org.jetbrains.annotations.Nullable

/**
* @author Alexander Podkhalyuzin
* Date: 22.02.2008
*/

class ScStableCodeReferenceElementImpl(node: ASTNode) extends ScalaPsiElementImpl(node) with ScStableCodeReferenceElement {

  def bindToElement(element: PsiElement): PsiElement = {
    return this;
    //todo
  }

  def getVariants(): Array[Object] = _resolve(this, new CompletionProcessor(resolveKinds(true))).map(r => r.getElement) //todo
  
  override def toString: String = "CodeReferenceElement"

  object MyResolver extends ResolveCache.PolyVariantResolver[ScStableCodeReferenceElementImpl] {
    def resolve(ref: ScStableCodeReferenceElementImpl, incomplete: Boolean) = {
      _resolve(ref, new ResolveProcessor(ref.resolveKinds(false), refName))
    }
  }

  private def resolveKinds(incomplete : Boolean) = getParent match {
    case _: ScStableCodeReferenceElement => StdKinds.stableQualRef
    case _: ScImportExpr => StdKinds.stableQualRef
    case _: ScSimpleTypeElement => if (incomplete) StdKinds.stableQualOrClass else StdKinds.stableClass
    case _: ScImportSelector => StdKinds.stableImportSelector
    case _ => StdKinds.stableQualRef
  }

  private def _qualifier() : Option[ScStableCodeReferenceElement] = {
    if (getParent.isInstanceOf[ScImportSelector]) {
      return getParent.getParent/*ScImportSelectors*/.getParent.asInstanceOf[ScImportExpr].reference
    }
    qualifier
  }

  def _resolve(ref: ScStableCodeReferenceElementImpl, processor: BaseProcessor): Array[ResolveResult] = {
    _qualifier match {
      case None => {
        def treeWalkUp(place: PsiElement, lastParent: PsiElement): Unit = {
          place match {
            case null => ()
            case p => {
              if (!p.processDeclarations(processor,
              ResolveState.initial(), //todo
              lastParent, ref)) return ()
              treeWalkUp(place.getParent, place)
            }
          }
        }
        treeWalkUp(ref, null)
      }
      case Some(q) => {
        q.bind match {
          case None =>
          case Some(other) => {
            other.element.processDeclarations(processor, ResolveState.initial(), //todo
            null, ScStableCodeReferenceElementImpl.this)
          }
        }
      }
    }
    processor.getCandidates.toArray
  }

  def multiResolve(incomplete: Boolean) = {
    getManager.asInstanceOf[PsiManagerEx].getResolveCache.resolveWithCaching(this, MyResolver, false, incomplete)
  }

  def getType() = {
    bind match {
      case None => null
      case Some(ScalaResolveResult(td: ScTypeDefinition, s)) => new ScParameterizedType(td, s)
      case Some(ScalaResolveResult(other, _)) => new ScDesignatorType(other)
    }
  }

  def nameId: PsiElement = findChildByType(ScalaTokenTypes.tIDENTIFIER)
}