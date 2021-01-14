package sangria.schema

import language.{existentials, postfixOps}
import sangria.ast
import sangria.ast.{AstLocation, OperationType, TypeDefinition}
import sangria.execution.MaterializedSchemaValidationError
import sangria.parser.SourceMapper
import sangria.renderer.QueryRenderer
import sangria.util.Cache
import sangria.validation._

import scala.reflect.ClassTag
import scala.collection.Set

class AstSchemaMaterializer[Ctx, F[_]] private (
    val document: ast.Document,
    builder: AstSchemaBuilder[Ctx, F]) {
  import AstSchemaMaterializer.extractSchemaInfo

  private val sdlOrigin = SDLOrigin(document)

  private val typeDefCache = Cache.empty[(MatOrigin, String), Type with Named]
  private val scalarAliasCache = Cache.empty[ScalarAlias[_, _], ScalarAlias[_, _]]

  private lazy val typeDefs: Vector[ast.TypeDefinition] = document.definitions.collect {
    case d: ast.TypeDefinition => d
  }

  private lazy val typeDefsMat: Vector[MaterializedType] =
    typeDefs.map(MaterializedType(sdlOrigin, _))

  private lazy val allDefinitions =
    document.definitions ++ builder.additionalTypeExtensionDefs ++ builder.additionalDirectiveDefs

  private lazy val additionalTypeDefsMap =
    builder.additionalTypes.groupBy(_.name).map { case (k, v) => (k, v.head) }

  private lazy val objectTypeExtensionDefs: Vector[ast.ObjectTypeExtensionDefinition] =
    allDefinitions.collect { case d: ast.ObjectTypeExtensionDefinition =>
      d
    }

  private lazy val interfaceTypeExtensionDefs: Vector[ast.InterfaceTypeExtensionDefinition] =
    allDefinitions.collect { case d: ast.InterfaceTypeExtensionDefinition =>
      d
    }

  private lazy val inputObjectTypeExtensionDefs: Vector[ast.InputObjectTypeExtensionDefinition] =
    allDefinitions.collect { case d: ast.InputObjectTypeExtensionDefinition =>
      d
    }

  private lazy val unionTypeExtensionDefs: Vector[ast.UnionTypeExtensionDefinition] =
    allDefinitions.collect { case d: ast.UnionTypeExtensionDefinition =>
      d
    }

  private lazy val enumTypeExtensionDefs: Vector[ast.EnumTypeExtensionDefinition] =
    allDefinitions.collect { case d: ast.EnumTypeExtensionDefinition =>
      d
    }

  private lazy val scalarTypeExtensionDefs: Vector[ast.ScalarTypeExtensionDefinition] =
    allDefinitions.collect { case d: ast.ScalarTypeExtensionDefinition =>
      d
    }

  private lazy val schemaExtensionDefs: Vector[ast.SchemaExtensionDefinition] =
    allDefinitions.collect { case d: ast.SchemaExtensionDefinition =>
      d
    }

  private lazy val directiveDefs: Vector[ast.DirectiveDefinition] = allDefinitions.collect {
    case d: ast.DirectiveDefinition => d
  }

  private lazy val directiveDefsMap = directiveDefs.groupBy(_.name)
  private lazy val typeDefsMap = typeDefs.groupBy(_.name)

  // maybe it would worth an effort to find more elegant way
  private var existingDefsMat: Map[String, MaterializedType] = Map.empty

  def extend[Val](schema: Schema[Ctx, Val, F]): Schema[Ctx, Val, F] = {
    val existingOrigin = ExistingSchemaOrigin(schema)

    val defErrors = validateExtensions(schema)

    if (defErrors.nonEmpty) throw MaterializedSchemaValidationError(defErrors)

    if (typeDefs.isEmpty &&
      objectTypeExtensionDefs.isEmpty &&
      interfaceTypeExtensionDefs.isEmpty &&
      enumTypeExtensionDefs.isEmpty &&
      scalarTypeExtensionDefs.isEmpty &&
      inputObjectTypeExtensionDefs.isEmpty &&
      unionTypeExtensionDefs.isEmpty)
      schema
    else {
      existingDefsMat = schema.allTypes.mapValues(MaterializedType(existingOrigin, _)).toMap

      val queryType = getTypeFromDef(existingOrigin, schema.query)

      AstSchemaMaterializer.findOperationsTypes(
        schemaExtensionDefs.flatMap(_.operationTypes),
        document.sourceMapper,
        true,
        schema.mutation.isDefined,
        schema.subscription.isDefined) match {
        case Left(errors) => throw MaterializedSchemaValidationError(errors)
        case Right((_, mutationExt, subscriptionExt)) =>
          val mutationType =
            mutationExt
              .map(getObjectType(sdlOrigin, _).asInstanceOf[ObjectType[Ctx, Val, F]])
              .orElse(schema.mutation)
              .map(getTypeFromDef(existingOrigin, _))
          val subscriptionType =
            subscriptionExt
              .map(getObjectType(sdlOrigin, _).asInstanceOf[ObjectType[Ctx, Val, F]])
              .orElse(schema.subscription)
              .map(getTypeFromDef(existingOrigin, _))
          val directives = directiveDefs.flatMap(buildDirective(existingOrigin, _))

          val (referenced, notReferenced) = findUnusedTypes()

          builder.extendSchema[Val](
            schema,
            schemaExtensionDefs.toList,
            queryType,
            mutationType,
            subscriptionType,
            (notReferenced ++ findUnusedTypes(schema, referenced)).toList,
            schema.directives.map(
              builder.transformDirective(existingOrigin, _, this)) ++ directives,
            this
          )
      }
    }
  }

  lazy val build: Schema[Ctx, Any, F] = {
    val defErrors = validateDefinitions

    extractSchemaInfo(document, typeDefs, schemaExtensionDefs) match {
      case Left(schemaErrors) => throw MaterializedSchemaValidationError(schemaErrors ++ defErrors)
      case _ if defErrors.nonEmpty => throw MaterializedSchemaValidationError(defErrors)
      case Right(schemaInfo) =>
        val queryType = getObjectType(sdlOrigin, schemaInfo.query)
        val mutationType = schemaInfo.mutation.map(getObjectType(sdlOrigin, _))
        val subscriptionType = schemaInfo.subscription.map(getObjectType(sdlOrigin, _))
        val directives = directiveDefs
          .filterNot(d => Schema.isBuiltInDirective(d.name))
          .flatMap(buildDirective(sdlOrigin, _))

        builder.buildSchema(
          schemaInfo.definition,
          schemaExtensionDefs.toList,
          queryType,
          mutationType,
          subscriptionType,
          findUnusedTypes()._2.toList,
          BuiltinDirectives ++ directives,
          this
        )
    }
  }

  lazy val definitions: Vector[Named] = {
    val defErrors = validateDefinitions

    if (defErrors.nonEmpty) throw MaterializedSchemaValidationError(defErrors)

    val directives = directiveDefs
      .filterNot(d => Schema.isBuiltInDirective(d.name))
      .flatMap(buildDirective(sdlOrigin, _))
    val unused = findUnusedTypes()

    unused._1.toVector.map(getNamedType(sdlOrigin, _, None)) ++ unused._2 ++ directives
  }

  def validateExtensions(schema: Schema[Ctx, _, F]): Vector[Violation] = {
    val nestedErrors = Vector(
      typeDefsMap.toVector.collect {
        case (name, defs) if defs.size > 1 =>
          NonUniqueTypeDefinitionViolation(
            name,
            document.sourceMapper,
            defs.flatMap(_.location).toList)
        case (name, defs) if schema.allTypes contains name =>
          ExistingTypeViolation(name, document.sourceMapper, defs.flatMap(_.location).toList)
      },
      directiveDefsMap.toVector.collect {
        case (name, defs) if defs.size > 1 =>
          NonUniqueDirectiveDefinitionViolation(
            name,
            document.sourceMapper,
            defs.flatMap(_.location).toList)
        case (name, defs) if schema.directivesByName contains name =>
          NonUniqueDirectiveDefinitionViolation(
            name,
            document.sourceMapper,
            defs.flatMap(_.location).toList)
      },
      objectTypeExtensionDefs.flatMap(
        validateExtensions[ObjectType[_, _, F], ast.ObjectTypeDefinition](schema, _, "object")),
      interfaceTypeExtensionDefs.flatMap(
        validateExtensions[InterfaceType[_, _, F], ast.InterfaceTypeDefinition](
          schema,
          _,
          "interface")),
      enumTypeExtensionDefs.flatMap(
        validateExtensions[EnumType[_], ast.EnumTypeDefinition](schema, _, "enum")),
      inputObjectTypeExtensionDefs.flatMap(
        validateExtensions[InputObjectType[_], ast.InputObjectTypeDefinition](
          schema,
          _,
          "input-object")),
      scalarTypeExtensionDefs.flatMap(
        validateExtensions[ScalarType[_], ast.ScalarTypeDefinition](schema, _, "scalar")),
      unionTypeExtensionDefs.flatMap(
        validateExtensions[UnionType[_, F], ast.UnionTypeDefinition](schema, _, "union"))
    )

    nestedErrors.flatten
  }

  private def validateExtensions[T1: ClassTag, T2: ClassTag](
      schema: Schema[Ctx, _, F],
      ext: ast.TypeExtensionDefinition,
      typeKind: String): Option[Violation] = {
    val instClass = implicitly[ClassTag[T1]].runtimeClass
    val astClass = implicitly[ClassTag[T2]].runtimeClass

    typeDefsMap.get(ext.name).map(_.head) match {
      case Some(tpe) if astClass.isAssignableFrom(tpe.getClass) => None
      case Some(tpe) =>
        Some(
          TypeExtensionOnWrongKindViolation(
            typeKind,
            tpe.name,
            document.sourceMapper,
            ext.location.toList))
      case None =>
        schema.allTypes.get(ext.name) match {
          case Some(tpe) if instClass.isAssignableFrom(tpe.getClass) => None
          case Some(tpe) =>
            Some(
              TypeExtensionOnWrongKindViolation(
                typeKind,
                tpe.name,
                document.sourceMapper,
                ext.location.toList))
          case None => validateExtensionsAdditional(instClass, astClass, ext, typeKind)
        }
    }
  }

  def validateDefinitions: Vector[Violation] = {
    val nestedErrors = Vector(
      typeDefsMap.find(_._2.size > 1).toVector.map { case (name, defs) =>
        NonUniqueTypeDefinitionViolation(
          name,
          document.sourceMapper,
          defs.flatMap(_.location).toList)
      },
      directiveDefsMap.find(_._2.size > 1).toVector.map { case (name, defs) =>
        NonUniqueDirectiveDefinitionViolation(
          name,
          document.sourceMapper,
          defs.flatMap(_.location).toList)
      },
      objectTypeExtensionDefs.flatMap(
        validateExtensionsAst[ObjectType[_, _, F], ast.ObjectTypeDefinition](_, "object")),
      interfaceTypeExtensionDefs.flatMap(
        validateExtensionsAst[InterfaceType[_, _, F], ast.InterfaceTypeDefinition](_, "interface")),
      enumTypeExtensionDefs.flatMap(
        validateExtensionsAst[EnumType[_], ast.EnumTypeDefinition](_, "enum")),
      inputObjectTypeExtensionDefs.flatMap(
        validateExtensionsAst[InputObjectType[_], ast.InputObjectTypeDefinition](
          _,
          "input-object")),
      scalarTypeExtensionDefs.flatMap(
        validateExtensionsAst[ScalarType[_], ast.ScalarTypeDefinition](_, "scalar")),
      unionTypeExtensionDefs.flatMap(
        validateExtensionsAst[UnionType[_, F], ast.UnionTypeDefinition](_, "union"))
    )

    nestedErrors.flatten
  }

  private def validateExtensionsAst[T1: ClassTag, T2: ClassTag](
      ext: ast.TypeExtensionDefinition,
      typeKind: String): Option[Violation] = {
    val instClass = implicitly[ClassTag[T1]].runtimeClass
    val astClass = implicitly[ClassTag[T2]].runtimeClass

    typeDefsMap.get(ext.name).map(_.head) match {
      case Some(tpe) if astClass.isAssignableFrom(tpe.getClass) => None
      case Some(tpe) =>
        Some(
          TypeExtensionOnWrongKindViolation(
            typeKind,
            tpe.name,
            document.sourceMapper,
            ext.location.toList))
      case None => validateExtensionsAdditional(instClass, astClass, ext, typeKind)
    }
  }

  private def validateExtensionsAdditional(
      instClass: Class[_],
      astClass: Class[_],
      ext: ast.TypeExtensionDefinition,
      typeKind: String) =
    additionalTypeDefsMap.get(ext.name) match {
      case Some(t) =>
        t match {
          case BuiltMaterializedTypeInst(_, tpe) if instClass.isAssignableFrom(tpe.getClass) => None
          case BuiltMaterializedTypeInst(_, tpe) =>
            Some(
              TypeExtensionOnWrongKindViolation(
                typeKind,
                tpe.name,
                document.sourceMapper,
                ext.location.toList))
          case MaterializedTypeInst(_, tpe) if instClass.isAssignableFrom(tpe.getClass) => None
          case MaterializedTypeInst(_, tpe) =>
            Some(
              TypeExtensionOnWrongKindViolation(
                typeKind,
                tpe.name,
                document.sourceMapper,
                ext.location.toList))
          case MaterializedTypeAst(_, tpe) if astClass.isAssignableFrom(tpe.getClass) => None
          case MaterializedTypeAst(_, tpe) =>
            Some(
              TypeExtensionOnWrongKindViolation(
                typeKind,
                tpe.name,
                document.sourceMapper,
                ext.location.toList))
        }
      case None =>
        Some(
          TypeExtensionOnNonExistingTypeViolation(
            ext.name,
            document.sourceMapper,
            ext.location.toList))
    }

  def findUnusedTypes(): (Set[String], Vector[Type with Named]) = {
    resolveAllLazyFields()

    val referenced = typeDefCache.mapToSet((_, v) => v.name)
    val notReferenced =
      typeDefs.filterNot(tpe => Schema.isBuiltInType(tpe.name) || referenced.contains(tpe.name))
    val notReferencedAdd = builder.additionalTypes.filterNot(tpe =>
      Schema.isBuiltInType(tpe.name) || referenced.contains(tpe.name))

    referenced -> (notReferenced.map(tpe =>
      getNamedType(sdlOrigin, tpe.name, tpe.location)) ++ notReferencedAdd.map(tpe =>
      getNamedType(tpe.origin, tpe.name, tpe.location)))
  }

  def findUnusedTypes(schema: Schema[_, _, F], referenced: Set[String]): Vector[Type with Named] = {
    val existingOrigin = ExistingSchemaOrigin(schema)

    resolveAllLazyFields()

    val notReferenced = schema.typeList.filterNot(tpe =>
      Schema.isBuiltInType(tpe.name) || referenced.contains(tpe.name))

    notReferenced.map(tpe => getTypeFromDef(existingOrigin, tpe))
  }

  // TODO: think about better solution
  def resolveAllLazyFields(): Unit = {
    var prevCount = 0
    var newCount = 0
    var iteration = 0

    do {
      prevCount = typeDefCache.size
      iteration += 1

      typeDefCache.forEachValue {
        case o: ObjectLikeType[_, _, _] => o.fields
        case o: InputObjectType[_] => o.fields
        case _ => // do nothing
      }

      newCount = typeDefCache.size
    } while (prevCount != newCount && iteration < 20)
  }

  def getTypeFromExistingType(origin: MatOrigin, tpe: OutputType[_]): OutputType[Any] = tpe match {
    case ListType(ofType) => ListType(getTypeFromExistingType(origin, ofType))
    case OptionType(ofType) => OptionType(getTypeFromExistingType(origin, ofType))
    case t: Named => getTypeFromDef(origin, t)
  }

  def getInputTypeFromExistingType(origin: MatOrigin, tpe: InputType[_]): InputType[Any] =
    tpe match {
      case ListInputType(ofType) => ListInputType(getInputTypeFromExistingType(origin, ofType))
      case OptionInputType(ofType) => OptionInputType(getInputTypeFromExistingType(origin, ofType))
      case t: Named => getTypeFromDef(origin, t)
    }

  def getTypeFromDef[T <: Type with Named](origin: MatOrigin, tpe: T): T =
    tpe match {
      case alias: ScalarAlias[Any, Any] @unchecked =>
        scalarAliasCache.getOrElseUpdate(alias, extendScalarAlias(origin, alias)).asInstanceOf[T]
      case _ =>
        getNamedType(origin, tpe.name, None).asInstanceOf[T]
    }

  def buildDirective(origin: MatOrigin, directive: ast.DirectiveDefinition) =
    BuiltinDirectives
      .find(_.name == directive.name)
      .orElse(builder.buildDirective(
        origin,
        directive,
        directive.arguments.flatMap(buildArgument(origin, Left(directive), None, _)) toList,
        directive.locations.map(buildDirectiveLocation) toSet,
        this
      ))

  def getObjectType(origin: MatOrigin, tpe: ast.NamedType): ObjectType[Ctx, Any, F] =
    getOutputType(origin, tpe, optional = false) match {
      case obj: ObjectType[_, _, F] => obj.asInstanceOf[ObjectType[Ctx, Any, F]]
      case _ =>
        throw MaterializedSchemaValidationError(
          Vector(
            InvalidTypeUsageViolation(
              "object",
              QueryRenderer.render(tpe),
              document.sourceMapper,
              tpe.location.toList)))
    }

  def getScalarType(origin: MatOrigin, tpe: ast.NamedType): ScalarType[Any] =
    getOutputType(origin, tpe, optional = false) match {
      case obj: ScalarType[_] => obj.asInstanceOf[ScalarType[Any]]
      case _ =>
        throw MaterializedSchemaValidationError(
          Vector(
            InvalidTypeUsageViolation(
              "scalar",
              QueryRenderer.render(tpe),
              document.sourceMapper,
              tpe.location.toList)))
    }

  def getInterfaceType(origin: MatOrigin, tpe: ast.NamedType): InterfaceType[Ctx, Any, F] =
    getOutputType(origin, tpe, optional = false) match {
      case obj: InterfaceType[_, _, F] => obj.asInstanceOf[InterfaceType[Ctx, Any, F]]
      case _ =>
        throw MaterializedSchemaValidationError(
          Vector(
            InvalidTypeUsageViolation(
              "interface",
              QueryRenderer.render(tpe),
              document.sourceMapper,
              tpe.location.toList)))
    }

  def getInputType(
      origin: MatOrigin,
      tpe: ast.Type,
      replacementNamedType: Option[InputType[_]] = None,
      optional: Boolean = true): InputType[_] =
    tpe match {
      case ast.ListType(ofType, _) if optional =>
        OptionInputType(ListInputType(getInputType(origin, ofType, replacementNamedType, true)))
      case ast.ListType(ofType, _) =>
        ListInputType(getInputType(origin, ofType, replacementNamedType, true))
      case ast.NotNullType(ofType, _) => getInputType(origin, ofType, replacementNamedType, false)
      case ast.NamedType(name, _) =>
        replacementNamedType.getOrElse(getNamedType(origin, name, tpe.location)) match {
          case input: InputType[_] if optional => OptionInputType(input)
          case input: InputType[_] => input
          case _ =>
            throw MaterializedSchemaValidationError(
              Vector(
                InvalidTypeUsageViolation(
                  "input type",
                  QueryRenderer.render(tpe),
                  document.sourceMapper,
                  tpe.location.toList)))
        }
    }

  def getOutputType(
      origin: MatOrigin,
      tpe: ast.Type,
      replacementNamedType: Option[OutputType[_]] = None,
      optional: Boolean = true): OutputType[_] =
    tpe match {
      case ast.ListType(ofType, _) if optional =>
        OptionType(ListType(getOutputType(origin, ofType, replacementNamedType, true)))
      case ast.ListType(ofType, _) =>
        ListType(getOutputType(origin, ofType, replacementNamedType, true))
      case ast.NotNullType(ofType, _) => getOutputType(origin, ofType, replacementNamedType, false)
      case ast.NamedType(name, _) =>
        replacementNamedType.getOrElse(getNamedType(origin, name, tpe.location)) match {
          case out: OutputType[_] if optional => OptionType(out)
          case out: OutputType[_] => out
          case _ =>
            throw MaterializedSchemaValidationError(
              Vector(
                InvalidTypeUsageViolation(
                  "output type",
                  QueryRenderer.render(tpe),
                  document.sourceMapper,
                  tpe.location.toList)))
        }
    }

  def getNamedType(
      origin: MatOrigin,
      typeName: String,
      location: Option[AstLocation]): Type with Named =
    typeDefCache.getOrElseUpdate(
      origin -> typeName,
      Schema.getBuiltInType(typeName).getOrElse {
        val existing = existingDefsMat.get(typeName).toVector
        val sdl = typeDefsMat.filter(_.name == typeName)
        val additional = builder.additionalTypes.filter(_.name == typeName).toVector

        val allCandidates = existing ++ sdl ++ additional

        val builtType =
          if (allCandidates.size > 1) {
            val resolved = builder.resolveNameConflict(
              origin,
              allCandidates ++
                typeDefCache
                  .find((_, v) => v.name == typeName)
                  .map { case ((o, _), v) => BuiltMaterializedTypeInst(o, v) }
                  .toVector)

            if (!resolved.isInstanceOf[BuiltMaterializedTypeInst] && typeDefCache.keyExists(
                _._2 == resolved.name))
              throw SchemaMaterializationException(
                "Name conflict resolution produced already existing type name")
            else
              getNamedType(origin, resolved)
          } else if (allCandidates.nonEmpty) {
            getNamedType(origin, allCandidates.head)
          } else None

        builtType.getOrElse(
          throw MaterializedSchemaValidationError(Vector(
            UnknownTypeViolation(typeName, Seq.empty, document.sourceMapper, location.toList))))
      }
    )

  def getNamedType(origin: MatOrigin, tpe: MaterializedType): Option[Type with Named] =
    tpe match {
      case BuiltMaterializedTypeInst(o, t) => Some(t)
      case MaterializedTypeInst(o, t) => Some(extendType(o, t))
      case MaterializedTypeAst(o, t) => buildType(o, t)
    }

  def buildType(origin: MatOrigin, definition: TypeDefinition): Option[Type with Named] =
    definition match {
      case d: ast.ObjectTypeDefinition => buildObjectDef(origin, d)
      case d: ast.InterfaceTypeDefinition => buildInterfaceDef(origin, d)
      case d: ast.UnionTypeDefinition => buildUnionDef(origin, d)
      case d: ast.InputObjectTypeDefinition => buildInputObjectDef(origin, d)
      case d: ast.ScalarTypeDefinition => buildScalarDef(origin, d)
      case d: ast.EnumTypeDefinition => buildEnumDef(origin, d)
    }

  def extendType(origin: MatOrigin, existingType: Type with Named): Type with Named =
    existingType match {
      case tpe: ScalarType[_] =>
        builder.transformScalarType(origin, findScalarExtensions(tpe.name), tpe, this)
      case tpe: ScalarAlias[_, _] =>
        extendScalarAlias(origin, tpe.asInstanceOf[ScalarAlias[Any, Any]])
      case tpe: EnumType[_] => extendEnumType(origin, tpe)
      case tpe: InputObjectType[_] => extendInputObjectType(origin, tpe)
      case tpe: UnionType[Ctx, F] => extendUnionType(origin, tpe)
      case tpe: ObjectType[Ctx, _, F] => extendObjectType(origin, tpe)
      case tpe: InterfaceType[Ctx, _, F] => extendInterfaceType(origin, tpe)
    }

  def buildField(
      origin: MatOrigin,
      typeDefinition: Either[ast.TypeDefinition, ObjectLikeType[Ctx, _, F]],
      extensions: Vector[ast.ObjectLikeTypeExtensionDefinition],
      field: ast.FieldDefinition) = {
    val args = field.arguments.flatMap(buildArgument(origin, typeDefinition, Some(field), _)) toList
    val fieldType = builder.buildFieldType(origin, typeDefinition, extensions, field, args, this)

    builder.buildField(origin, typeDefinition, extensions, field, fieldType, args, this)
  }

  def extendField(
      origin: MatOrigin,
      tpe: Option[ObjectLikeType[Ctx, _, F]],
      field: Field[Ctx, _, F]) = {
    val f = field.asInstanceOf[Field[Ctx, Any, F]]

    builder.extendField(
      origin,
      tpe,
      f,
      builder.extendFieldType(origin, tpe, f, this),
      f.arguments.map(extendArgument(origin, tpe, f, _)),
      this)
  }

  def extendArgument(
      origin: MatOrigin,
      tpe: Option[ObjectLikeType[Ctx, _, F]],
      field: Field[Ctx, Any, F],
      argument: Argument[_]) = {
    val a = argument.asInstanceOf[Argument[Any]]

    builder.extendArgument(
      origin,
      tpe,
      field,
      a,
      builder.extendArgumentType(origin, tpe, field, a, this),
      this)
  }

  def extendInputField(origin: MatOrigin, tpe: InputObjectType[_], field: InputField[_]) = {
    val f = field.asInstanceOf[InputField[Any]]

    builder.extendInputField(
      origin,
      tpe,
      f,
      builder.extendInputFieldType(origin, tpe, f, this),
      this)
  }

  def buildObjectDef(origin: MatOrigin, tpe: ast.ObjectTypeDefinition) = {
    val extensions = findObjectExtensions(tpe.name)

    builder.buildObjectType(
      origin,
      tpe,
      extensions.toList,
      () => buildFields(origin, tpe, tpe.fields, extensions).toList,
      buildInterfaces(origin, tpe, tpe.interfaces, extensions).toList,
      this
    )
  }

  def extendObjectType(origin: MatOrigin, tpe: ObjectType[Ctx, _, F]) = {
    val extensions = findObjectExtensions(tpe.name)

    builder.extendObjectType(
      origin,
      tpe,
      extensions.toList,
      () => extendFields(origin, tpe, extensions),
      extendInterfaces(origin, tpe, extensions),
      this)
  }

  def buildInterfaceDef(origin: MatOrigin, tpe: ast.InterfaceTypeDefinition) = {
    val extensions = findInterfaceExtensions(tpe.name)

    builder.buildInterfaceType(
      origin,
      tpe,
      extensions.toList,
      () => buildFields(origin, tpe, tpe.fields, extensions).toList,
      this)
  }

  def extendEnumType(origin: MatOrigin, tpe: EnumType[_]) = {
    val extensions = findEnumExtensions(tpe.name)
    val extraValues = extensions.flatMap(_.values)
    val extraDirs = extensions.flatMap(_.directives)

    val ev = extraValues.flatMap(buildEnumValue(origin, Right(tpe), _, extensions))

    val extendedType =
      if (ev.nonEmpty || extraDirs.nonEmpty)
        tpe.copy(values = tpe.values ++ ev, astDirectives = tpe.astDirectives ++ extraDirs)
      else tpe

    builder.transformEnumType(origin, extensions, extendedType, this)
  }

  def extendInputObjectType(origin: MatOrigin, tpe: InputObjectType[_]) = {
    val extensions = findInputObjectExtensions(tpe.name)
    val extraFields = extensions.flatMap(_.fields)

    val fieldsFn = () => {
      val ef = extraFields.flatMap(buildInputField(origin, Right(tpe), _, extensions)) toList
      val f = tpe.fields.map(extendInputField(origin, tpe, _))

      f ++ ef
    }

    builder.transformInputObjectType(origin, extensions, tpe, fieldsFn, this)
  }

  def extendInterfaceType(origin: MatOrigin, tpe: InterfaceType[Ctx, _, F]) = {
    val extensions = findInterfaceExtensions(tpe.name)

    builder.extendInterfaceType(
      origin,
      tpe,
      extensions.toList,
      () => extendFields(origin, tpe, extensions),
      this)
  }

  def buildInterfaces(
      origin: MatOrigin,
      tpe: ast.ObjectTypeDefinition,
      interfaces: Vector[ast.NamedType],
      extensions: Vector[ast.ObjectTypeExtensionDefinition]) = {
    val extraInts = extensions.flatMap(_.interfaces)
    val allInts = interfaces ++ extraInts

    allInts.map(getInterfaceType(origin, _))
  }

  def extendInterfaces(
      origin: MatOrigin,
      tpe: ObjectType[Ctx, _, F],
      extensions: Vector[ast.ObjectTypeExtensionDefinition]) = {
    val extraInts = extensions.flatMap(_.interfaces)

    val ei = extraInts.map(getInterfaceType(origin, _))
    val oi = tpe.interfaces.map(getTypeFromDef(origin, _).asInstanceOf[InterfaceType[Ctx, Any, F]])

    (ei ++ oi).toList
  }

  def buildFields(
      origin: MatOrigin,
      tpe: TypeDefinition,
      fieldDefs: Vector[ast.FieldDefinition],
      extensions: Vector[ast.ObjectLikeTypeExtensionDefinition]) = {
    val extraFields = extensions.flatMap(_.fields)

    val withExtensions = fieldDefs ++ extraFields

    val addFields = builder.buildAdditionalFields(origin, extensions, tpe, this).flatMap {
      case MaterializedFieldAst(o, ast) => buildField(o, Left(tpe), extensions, ast)
      case MaterializedFieldInst(o, definition) => Some(extendField(o, None, definition))
    }

    withExtensions.flatMap(buildField(origin, Left(tpe), extensions, _)) ++ addFields
  }

  def extendFields(
      origin: MatOrigin,
      tpe: ObjectLikeType[Ctx, _, F],
      extensions: Vector[ast.ObjectLikeTypeExtensionDefinition]) = {
    val extraFields = extensions.flatMap(e => e.fields.map(e -> _))

    val ef = extraFields.flatMap(f => buildField(origin, Right(tpe), extensions, f._2))
    val of = tpe.uniqueFields.toList.map(extendField(origin, Some(tpe), _))

    of ++ ef
  }

  def findObjectExtensions(typeName: String) =
    objectTypeExtensionDefs.filter(_.name == typeName)

  def findInterfaceExtensions(typeName: String) =
    interfaceTypeExtensionDefs.filter(_.name == typeName)

  def findScalarExtensions(typeName: String) =
    scalarTypeExtensionDefs.filter(_.name == typeName)

  def findInputObjectExtensions(typeName: String) =
    inputObjectTypeExtensionDefs.filter(_.name == typeName)

  def findUnionExtensions(typeName: String) =
    unionTypeExtensionDefs.filter(_.name == typeName)

  def findEnumExtensions(typeName: String) =
    enumTypeExtensionDefs.filter(_.name == typeName)

  def buildUnionDef(origin: MatOrigin, tpe: ast.UnionTypeDefinition) = {
    val extensions = findUnionExtensions(tpe.name)
    val extraTypes = extensions.flatMap(_.types)
    val withExtensions = tpe.types ++ extraTypes

    builder.buildUnionType(
      origin,
      extensions,
      tpe,
      withExtensions.map(getObjectType(origin, _)) toList,
      this)
  }

  def extendUnionType(origin: MatOrigin, tpe: UnionType[Ctx, F]) = {
    val extensions = findUnionExtensions(tpe.name)
    val extraTypes = extensions.flatMap(_.types)

    val t = tpe.types.map(getTypeFromDef(origin, _))
    val et = extraTypes.map(getObjectType(origin, _)) toList

    builder.extendUnionType(origin, extensions, tpe, t ++ et, this)
  }

  def extendScalarAlias(origin: MatOrigin, alias: ScalarAlias[Any, Any]) = {
    val extensions = findScalarExtensions(alias.aliasFor.name)

    builder.extendScalarAlias(
      origin,
      extensions,
      alias,
      getTypeFromDef(origin, alias.aliasFor),
      this)
  }

  def buildInputObjectDef(origin: MatOrigin, tpe: ast.InputObjectTypeDefinition) = {
    val extensions = findInputObjectExtensions(tpe.name)
    val extraFields = extensions.flatMap(_.fields)
    val withExtensions = tpe.fields ++ extraFields

    builder.buildInputObjectType(
      origin,
      extensions,
      tpe,
      () => withExtensions.flatMap(buildInputField(origin, Left(tpe), _, extensions)) toList,
      this)
  }

  def buildScalarDef(origin: MatOrigin, tpe: ast.ScalarTypeDefinition) = {
    val extensions = findScalarExtensions(tpe.name)

    builder.buildScalarType(origin, extensions, tpe, this)
  }

  private def buildEnumDef(origin: MatOrigin, tpe: ast.EnumTypeDefinition) = {
    val extensions = findEnumExtensions(tpe.name)
    val extraValues = extensions.flatMap(_.values)
    val withExtensions = tpe.values ++ extraValues

    builder.buildEnumType(
      origin,
      extensions,
      tpe,
      withExtensions.flatMap(buildEnumValue(origin, Left(tpe), _, extensions)) toList,
      this)
  }

  private def buildEnumValue(
      origin: MatOrigin,
      typeDef: Either[ast.EnumTypeDefinition, EnumType[_]],
      value: ast.EnumValueDefinition,
      extensions: Vector[ast.EnumTypeExtensionDefinition]) =
    builder.buildEnumValue(origin, extensions, typeDef, value, this)

  def buildDefault(defaultValue: Option[ast.Value]) =
    defaultValue.map(dv => dv -> sangria.marshalling.queryAst.queryAstToInput)

  def buildArgument(
      origin: MatOrigin,
      typeDefinition: Either[ast.TypeSystemDefinition, ObjectLikeType[Ctx, _, F]],
      fieldDef: Option[ast.FieldDefinition],
      value: ast.InputValueDefinition) = {
    val default = buildDefault(value.defaultValue)
    val tpe = builder.buildArgumentType(origin, typeDefinition, fieldDef, value, default, this)

    builder.buildArgument(origin, typeDefinition, fieldDef, value, tpe, default, this)
  }

  def buildInputField(
      origin: MatOrigin,
      typeDef: Either[ast.InputObjectTypeDefinition, InputObjectType[_]],
      value: ast.InputValueDefinition,
      extensions: Vector[ast.InputObjectTypeExtensionDefinition]) = {
    val default = buildDefault(value.defaultValue)
    val tpe = builder.buildInputFieldType(origin, extensions, typeDef, value, default, this)

    builder.buildInputField(origin, extensions, typeDef, value, tpe, default, this)
  }

  def buildDirectiveLocation(loc: ast.DirectiveLocation) =
    try DirectiveLocation.fromString(loc.name)
    catch {
      case e: MatchError =>
        throw SchemaMaterializationException(s"Unknown directive location '${loc.name}'.")
    }
}

object AstSchemaMaterializer {
  case class SchemaInfo(
      query: ast.NamedType,
      mutation: Option[ast.NamedType],
      subscription: Option[ast.NamedType],
      definition: Option[ast.SchemaDefinition])

  def extractSchemaInfo(
      document: ast.Document,
      typeDefs: Vector[ast.TypeDefinition],
      extensions: Vector[ast.SchemaExtensionDefinition]): Either[Vector[Violation], SchemaInfo] = {
    val schemas = document.definitions.collect { case s: ast.SchemaDefinition => s }

    val schemaErrors =
      if (schemas.size > 1)
        Vector(
          NonUniqueSchemaDefinitionViolation(
            document.sourceMapper,
            schemas.flatMap(_.location).toList))
      else Vector.empty

    if (schemas.nonEmpty) {
      val validatedInfo =
        schemas.map { schema =>
          val allOperationTypes = schema.operationTypes ++ extensions.flatMap(_.operationTypes)

          findOperationsTypes(allOperationTypes, document.sourceMapper, false, false, false)
            .map { case (query, mutation, subscription) =>
              SchemaInfo(query.get, mutation, subscription, Some(schema))
            }
        }

      val typeErrors = validatedInfo.collect { case Left(errors) => errors }.flatten

      if (schemaErrors.nonEmpty || typeErrors.nonEmpty) Left(schemaErrors ++ typeErrors)
      else validatedInfo.head
    } else {
      typeDefs.find(_.name == "Query") match {
        case None =>
          Left(
            schemaErrors :+ NoQueryTypeViolation(document.sourceMapper, document.location.toList))

        case Some(_) if schemaErrors.nonEmpty =>
          Left(schemaErrors)

        case Some(query) =>
          val mutation = typeDefs.find(_.name == "Mutation").map(t => ast.NamedType(t.name))
          val subscription = typeDefs.find(_.name == "Subscription").map(t => ast.NamedType(t.name))

          findOperationsTypes(
            extensions.flatMap(_.operationTypes),
            document.sourceMapper,
            true,
            mutation.isDefined,
            subscription.isDefined).map { case (_, mutationExt, subscriptionExt) =>
            SchemaInfo(
              ast.NamedType(query.name),
              mutationExt.orElse(mutation),
              subscriptionExt.orElse(subscription),
              None)
          }
      }
    }
  }

  def findOperationsTypes(
      allOperationTypes: Vector[ast.OperationTypeDefinition],
      sourceMapper: Option[SourceMapper],
      queryAlreadyExists: Boolean,
      mutationAlreadyExists: Boolean,
      subscriptionAlreadyExists: Boolean): Either[
    Vector[Violation],
    (Option[ast.NamedType], Option[ast.NamedType], Option[ast.NamedType])] = {
    val queries = allOperationTypes.collect {
      case ast.OperationTypeDefinition(OperationType.Query, tpe, _, _) => tpe
    }
    val mutations = allOperationTypes.collect {
      case ast.OperationTypeDefinition(OperationType.Mutation, tpe, _, _) => tpe
    }
    val subscriptions = allOperationTypes.collect {
      case ast.OperationTypeDefinition(OperationType.Subscription, tpe, _, _) => tpe
    }

    val qErrors =
      if ((!queryAlreadyExists && queries.size != 1) || (queryAlreadyExists && queries.nonEmpty))
        Vector(
          NonUniqueRootTypeViolation("query", sourceMapper, queries.flatMap(_.location).toList))
      else Vector.empty

    val mErrors =
      if ((!mutationAlreadyExists && mutations.size > 1) || (mutationAlreadyExists && mutations.nonEmpty))
        qErrors :+ NonUniqueRootTypeViolation(
          "mutation",
          sourceMapper,
          queries.flatMap(_.location).toList)
      else qErrors

    val sErrors =
      if ((!subscriptionAlreadyExists && subscriptions.size > 1) || (subscriptionAlreadyExists && subscriptions.nonEmpty))
        mErrors :+ NonUniqueRootTypeViolation(
          "subscription",
          sourceMapper,
          queries.flatMap(_.location).toList)
      else mErrors

    if (sErrors.nonEmpty) Left(sErrors)
    else Right((queries.headOption, mutations.headOption, subscriptions.headOption))
  }

  def buildSchema[F[_]](document: ast.Document): Schema[Any, Any, F] =
    buildSchema[Any, F](document, AstSchemaBuilder.default)

  def buildSchema[Ctx, F[_]](
      document: ast.Document,
      builder: AstSchemaBuilder[Ctx, F]): Schema[Ctx, Any, F] =
    new AstSchemaMaterializer[Ctx, F](document, builder).build

  def definitions[F[_]](document: ast.Document): Vector[Named] =
    definitions[Any, F](document, AstSchemaBuilder.default)

  def definitions[Ctx, F[_]](
      document: ast.Document,
      builder: AstSchemaBuilder[Ctx, F]): Vector[Named] =
    new AstSchemaMaterializer[Ctx, F](document, builder).definitions

  def extendSchema[Ctx, Val, F[_]](
      schema: Schema[Ctx, Val, F],
      document: ast.Document,
      builder: AstSchemaBuilder[Ctx, F] = AstSchemaBuilder.default): Schema[Ctx, Val, F] =
    new AstSchemaMaterializer[Ctx, F](document, builder).extend(schema)
}
