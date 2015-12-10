package com.emotioncity.soriento

import java.lang.reflect.{Field, ParameterizedType}
import java.util
import javax.persistence.Id

import com.emotioncity.soriento.annotations._
import com.orientechnologies.orient.core.db.record.OTrackedSet
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.core.record.impl.ODocument

import scala.reflect.ClassTag
import scala.reflect.runtime.universe._


/**
 * Created by stream on 14.12.14.
 */

object ReflectionUtils {

  import scala.collection.JavaConverters._

  def constructor(t: Type): MethodMirror = {
    val m = runtimeMirror(getClass.getClassLoader)
    m.reflectClass(t.typeSymbol.asClass).reflectConstructor(t.decl(termNames.CONSTRUCTOR).asMethod)
  }

  def createCaseClass[T](map: ODocument)(implicit tag: TypeTag[T]): T = {
    val tpe = typeOf[T]
    createCaseClassByType(tpe, map).asInstanceOf[T]
  }

  def createCaseClassByType(tpe: Type, document: ODocument): Any = {
    val constr = constructor(tpe)
    val params = constr.symbol.paramLists.flatten // get constructor params
    val typeMap = params.map(symbol => symbol.name.toString -> symbol.typeSignature).toMap
    val input = document.toMap.asScala.map {
      case (k, v) =>
        typeMap.get(k) match {
          case None => (k, v)
          case Some(originalSignature) =>
            val (signature, optional) = if (originalSignature.<:<(typeOf[Option[_]])) {
              (originalSignature.typeArgs.head, true)
            } else {
              (originalSignature, false)
            }
            val valueType = v match {
              case m: ODocument =>
                if (signature.<:<(typeOf[ORID])) {
                  m.getIdentity
                } else if (signature.<:<(typeOf[ODocument])) {
                  m
                } else {
                  createCaseClassByType(signature, m)
                }
              case m: OTrackedSet[ODocument @unchecked] =>
                m.asScala.map(item => createCaseClassByType(signature.typeArgs.head, item)).toSet
              case m: util.ArrayList[ODocument @unchecked] =>
                m.asScala.toList.map(item => createCaseClassByType(signature.typeArgs.head, item))
              case m => m
            }
            k -> (if (optional) Some(valueType) else valueType)
        }
    }

    constr(params.map(_.name.toString).map(name => {
      input.get(name) match {
        case Some(value) => value
        case None =>
          val (signature, optional) = if (typeMap(name).<:<(typeOf[Option[_]])) {
            (typeMap(name).typeArgs.head, true)
          } else {
            (typeMap(name), false)
          }
          val xxx = if (signature.<:<(typeOf[ORID])) {
            document.getIdentity
          } else {
            null
          }
          if (optional) Option(xxx) else xxx
      }
    }).toSeq: _*) // invoke constructor

  }

  // return a human-readable type string for type argument 'T'
  // typeString[Int] returns "Int"
  def typeString(t: Type): String = {
    t match {
      case TypeRef(pre, sym, args) =>
        val ss = sym.toString.stripPrefix("trait ").stripPrefix("class ").stripPrefix("type ")
        val as = args.map(typeString)
        if (ss.startsWith("Function")) {
          val arity = args.length - 1
          "(" + as.take(arity).mkString(",") + ")" + "=>" + as.drop(arity).head
        } else {
          if (args.length <= 0) ss else ss + "[" + as.mkString(",") + "]"
        }
    }
  }

  /**
   * Powerful for determine type of Generic
   * @tparam T generic type
   * @return scala.reflect.runtime.universe.Type
   */
  def typeStringByTypeTag[T: TypeTag] = typeOf[T].typeArgs.head

  def typeStringByType(t: Type): Option[Type] = t.typeArgs.headOption

  def getTypeForClass[T](clazz: Class[T]): Type = {
    val runtimeMirrorT = runtimeMirror(clazz.getClassLoader)
    runtimeMirrorT.classSymbol(clazz).toType
  }

  def fieldsWithAnnotations(tpe: Type): Option[List[(String, List[Annotation])]] = {
    val companionSymbol = tpe.typeSymbol.companion
    companionSymbol
      .typeSignature
      .members
      .collectFirst { case method: MethodSymbol if method.name.toString == "apply" =>
      method.paramLists.head.map(p => p.name.toString -> p.annotations)
    }
  }

  def onlyFieldsWithAnnotations(tpe: Type): Option[List[(String, List[Annotation])]] = {
    //TODO use top method
    val companionSymbol = tpe.typeSymbol.companion
    companionSymbol
      .typeSignature
      .members
      .collectFirst { case method: MethodSymbol if method.name.toString == "apply" =>
      method.paramLists.head.map(p => p.name.toString -> p.annotations).filter(t => t._2.nonEmpty)
    }
  }

  def getOType[T](inName: String, field: Field)(implicit tag: ClassTag[T]): OType = {
    getOType(inName, field, tag.runtimeClass)
  }

  def getOType[T](inName: String, field: Field, clazz: Class[_]): OType = {
    val fieldClassName = field.getType.getName
    fieldClassName match {
      case "java.lang.Boolean" | "boolean" => OType.BOOLEAN
      case "java.lang.String" | "string" => OType.STRING
      case "java.lang.Byte" | "byte" => OType.BYTE
      case "java.lang.Short" | "short" => OType.SHORT
      case "java.lang.Integer" | "int" => OType.INTEGER
      case "java.lang.Long" | "long" => OType.LONG
      case "java.lang.Float" | "float" => OType.FLOAT
      case "java.lang.Double" | "double" => OType.DOUBLE
      case "java.util.Date" => OType.DATE
      /*case "scala.Option" =>
        val genericOpt = getGenericTypeClass(field)
        genericOpt match {
          case Some(t) =>
            println(s"Type for class: ${t.getSimpleName}")
            val propertyName = t.getSimpleName

          case None =>
            OType.ANY
        }*/
      //TODO support Option type not implemented yet, but in progress
      case _ =>
        val typeOfClass = getTypeForClass(clazz)
        val annotatedFields: List[(String, List[Annotation])] = onlyFieldsWithAnnotations(typeOfClass).get
        annotatedFields.find {
          case (name, listOfAnnotations) => name == inName
        }.map {
          case (name, listOfAnnotations) =>
            listOfAnnotations.head //TODO get only Soriento annotations!
        } match {
          case Some(annotation) =>
            annotation.tree.tpe match {
              case tpe if tpe =:= typeOf[Embedded] =>
                OType.EMBEDDED
              case tpe if tpe =:= typeOf[Linked] =>
                OType.LINK
              case tpe if tpe =:= typeOf[LinkSet] =>
                OType.LINKSET
              case tpe if tpe =:= typeOf[LinkList] =>
                OType.LINKLIST
              case tpe if tpe =:= typeOf[EmbeddedSet] =>
                OType.EMBEDDEDSET
              case tpe if tpe =:= typeOf[EmbeddedList] =>
                OType.EMBEDDEDLIST
              case _ =>
                //println("Unsupported annotation! " + annotation.tree.tpe)
                OType.ANY //TODO unsupported annotations
            }
          case None =>
            OType.ANY
        }
    }
  }

  def isId(name: String, clazz: Class[_]): Boolean = {
    val typeOfClass = getTypeForClass(clazz)
    val fieldsWithAnnotations: List[(String, List[Annotation])] = onlyFieldsWithAnnotations(typeOfClass).get
    fieldsWithAnnotations.exists(pair => pair._1 == name && pair._2.exists(annotation => annotation.tree.tpe =:= typeOf[Id]))
  }

  /**
   * Get RID if it present in object
   * TODO: More type safe!
   * @param cc case class
   * @return None if RID does not exist else Some(rid)
   */
  def rid(cc: Product): Option[ORID] = {
    val clazz = cc.getClass
    val fieldList: List[Field] = cc.getClass.getDeclaredFields.toList
    val idFieldOpt = fieldList.find(field => isId(field.getName, clazz))
    idFieldOpt match {
      case Some(idField) =>
        idField.setAccessible(true)
        getGenericTypeClass(idField) match {
          case Some(generic) => //Option[ORID]
            idField.get(cc).asInstanceOf[Option[ORID]]
          case None => //ORID
            getTypeForClass(idField.getType) match {
              case tpe if tpe =:= typeOf[ORID] =>
                Option(idField.get(cc).asInstanceOf[ORID])
              case _ =>
                None
            }
        }
      case None =>
        None
    }
  }

  def getGenericTypeClass(field: Field): Option[Class[_]] = {
    val genericType = field.getGenericType
    genericType match {
      case parametrizedType: ParameterizedType =>
        val parameterType = parametrizedType.getActualTypeArguments()(0)
        parameterType match {
          case value: Class[_] =>
            Option(value)
          case _ =>
            None
        }
      case _ =>
        None
    }
  }

}
