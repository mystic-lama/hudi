/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.avro;

import org.apache.hudi.common.util.Option;
import org.apache.hudi.exception.SchemaCompatibilityException;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.apache.hudi.common.util.ValidationUtils.checkState;

/**
 * Utils for Avro Schema.
 */
public class AvroSchemaUtils {

  private AvroSchemaUtils() {}

  /**
   * See {@link #isSchemaCompatible(Schema, Schema, boolean, boolean)} doc for more details
   */
  public static boolean isSchemaCompatible(Schema prevSchema, Schema newSchema) {
    return isSchemaCompatible(prevSchema, newSchema, true);
  }

  /**
   * See {@link #isSchemaCompatible(Schema, Schema, boolean, boolean)} doc for more details
   */
  public static boolean isSchemaCompatible(Schema prevSchema, Schema newSchema, boolean allowProjection) {
    return isSchemaCompatible(prevSchema, newSchema, true, allowProjection);
  }

  /**
   * Establishes whether {@code newSchema} is compatible w/ {@code prevSchema}, as
   * defined by Avro's {@link AvroSchemaCompatibility}.
   * From avro's compatibility standpoint, prevSchema is writer schema and new schema is reader schema.
   * {@code newSchema} is considered compatible to {@code prevSchema}, iff data written using {@code prevSchema}
   * could be read by {@code newSchema}
   *
   * @param prevSchema previous instance of the schema
   * @param newSchema new instance of the schema
   * @param checkNaming controls whether schemas fully-qualified names should be checked
   */
  public static boolean isSchemaCompatible(Schema prevSchema, Schema newSchema, boolean checkNaming, boolean allowProjection) {
    // NOTE: We're establishing compatibility of the {@code prevSchema} and {@code newSchema}
    //       as following: {@code newSchema} is considered compatible to {@code prevSchema},
    //       iff data written using {@code prevSchema} could be read by {@code newSchema}

    // In case schema projection is not allowed, new schema has to have all the same fields as the
    // old schema
    if (!allowProjection) {
      if (!canProject(prevSchema, newSchema)) {
        return false;
      }
    }

    AvroSchemaCompatibility.SchemaPairCompatibility result =
        AvroSchemaCompatibility.checkReaderWriterCompatibility(newSchema, prevSchema, checkNaming);
    return result.getType() == AvroSchemaCompatibility.SchemaCompatibilityType.COMPATIBLE;
  }

  /**
   * Check that each field in the prevSchema can be populated in the newSchema
   * @param prevSchema prev schema.
   * @param newSchema new schema
   * @return true if prev schema is a projection of new schema.
   */
  public static boolean canProject(Schema prevSchema, Schema newSchema) {
    return canProject(prevSchema, newSchema, Collections.emptySet());
  }

  /**
   * Check that each field in the prevSchema can be populated in the newSchema except specified columns
   * @param prevSchema prev schema.
   * @param newSchema new schema
   * @return true if prev schema is a projection of new schema.
   */
  public static boolean canProject(Schema prevSchema, Schema newSchema, Set<String> exceptCols) {
    return prevSchema.getFields().stream()
        .filter(f -> !exceptCols.contains(f.name()))
        .map(oldSchemaField -> SchemaCompatibility.lookupWriterField(newSchema, oldSchemaField))
        .noneMatch(Objects::isNull);
  }

  /**
   * Generates fully-qualified name for the Avro's schema based on the Table's name
   *
   * NOTE: PLEASE READ CAREFULLY BEFORE CHANGING
   *       This method should not change for compatibility reasons as older versions
   *       of Avro might be comparing fully-qualified names rather than just the record
   *       names
   */
  public static String getAvroRecordQualifiedName(String tableName) {
    String sanitizedTableName = HoodieAvroUtils.sanitizeName(tableName);
    return "hoodie." + sanitizedTableName + "." + sanitizedTableName + "_record";
  }

  /**
   * Validate whether the {@code targetSchema} is a valid evolution of {@code sourceSchema}.
   * Basically {@link #isCompatibleProjectionOf(Schema, Schema)} but type promotion in the
   * opposite direction
   */
  public static boolean isValidEvolutionOf(Schema sourceSchema, Schema targetSchema) {
    return (sourceSchema.getType() == Schema.Type.NULL) || isProjectionOfInternal(sourceSchema, targetSchema,
        AvroSchemaUtils::isAtomicSchemasCompatibleEvolution);
  }

  /**
   * Establishes whether {@code newReaderSchema} is compatible w/ {@code prevWriterSchema}, as
   * defined by Avro's {@link AvroSchemaCompatibility}.
   * {@code newReaderSchema} is considered compatible to {@code prevWriterSchema}, iff data written using {@code prevWriterSchema}
   * could be read by {@code newReaderSchema}
   * @param newReaderSchema new reader schema instance.
   * @param prevWriterSchema prev writer schema instance.
   * @return true if its compatible. else false.
   */
  private static boolean isAtomicSchemasCompatibleEvolution(Schema newReaderSchema, Schema prevWriterSchema) {
    // NOTE: Checking for compatibility of atomic types, we should ignore their
    //       corresponding fully-qualified names (as irrelevant)
    return isSchemaCompatible(prevWriterSchema, newReaderSchema, false, true);
  }

  /**
   * Validate whether the {@code targetSchema} is a "compatible" projection of {@code sourceSchema}.
   * Only difference of this method from {@link #isStrictProjectionOf(Schema, Schema)} is
   * the fact that it allows some legitimate type promotions (like {@code int -> long},
   * {@code decimal(3, 2) -> decimal(5, 2)}, etc.) that allows projection to have a "wider"
   * atomic type (whereas strict projection requires atomic type to be identical)
   */
  public static boolean isCompatibleProjectionOf(Schema sourceSchema, Schema targetSchema) {
    return isProjectionOfInternal(sourceSchema, targetSchema,
        AvroSchemaUtils::isAtomicSchemasCompatible);
  }

  private static boolean isAtomicSchemasCompatible(Schema oneAtomicType, Schema anotherAtomicType) {
    // NOTE: Checking for compatibility of atomic types, we should ignore their
    //       corresponding fully-qualified names (as irrelevant)
    return isSchemaCompatible(oneAtomicType, anotherAtomicType, false, true);
  }

  /**
   * Validate whether the {@code targetSchema} is a strict projection of {@code sourceSchema}.
   *
   * Schema B is considered a strict projection of schema A iff
   * <ol>
   *   <li>Schemas A and B are equal, or</li>
   *   <li>Schemas A and B are array schemas and element-type of B is a strict projection
   *   of the element-type of A, or</li>
   *   <li>Schemas A and B are map schemas and value-type of B is a strict projection
   *   of the value-type of A, or</li>
   *   <li>Schemas A and B are union schemas (of the same size) and every element-type of B
   *   is a strict projection of the corresponding element-type of A, or</li>
   *   <li>Schemas A and B are record schemas and every field of the record B has corresponding
   *   counterpart (w/ the same name) in the schema A, such that the schema of the field of the schema
   *   B is also a strict projection of the A field's schema</li>
   * </ol>
   */
  public static boolean isStrictProjectionOf(Schema sourceSchema, Schema targetSchema) {
    return isProjectionOfInternal(sourceSchema, targetSchema, Objects::equals);
  }

  private static boolean isProjectionOfInternal(Schema sourceSchema,
                                                Schema targetSchema,
                                                BiFunction<Schema, Schema, Boolean> atomicTypeEqualityPredicate) {
    if (sourceSchema.getType() == targetSchema.getType()) {
      if (sourceSchema.getType() == Schema.Type.RECORD) {
        for (Schema.Field targetField : targetSchema.getFields()) {
          Schema.Field sourceField = sourceSchema.getField(targetField.name());
          if (sourceField == null || !isProjectionOfInternal(sourceField.schema(), targetField.schema(), atomicTypeEqualityPredicate)) {
            return false;
          }
        }
        return true;
      } else if (sourceSchema.getType() == Schema.Type.ARRAY) {
        return isProjectionOfInternal(sourceSchema.getElementType(), targetSchema.getElementType(), atomicTypeEqualityPredicate);
      } else if (sourceSchema.getType() == Schema.Type.MAP) {
        return isProjectionOfInternal(sourceSchema.getValueType(), targetSchema.getValueType(), atomicTypeEqualityPredicate);
      } else if (sourceSchema.getType() == Schema.Type.UNION) {
        List<Schema> sourceNestedSchemas = sourceSchema.getTypes();
        List<Schema> targetNestedSchemas = targetSchema.getTypes();
        if (sourceNestedSchemas.size() != targetNestedSchemas.size()) {
          return false;
        }

        for (int i = 0; i < sourceNestedSchemas.size(); ++i) {
          if (!isProjectionOfInternal(sourceNestedSchemas.get(i), targetNestedSchemas.get(i), atomicTypeEqualityPredicate)) {
            return false;
          }
        }
        return true;
      }
    }

    return atomicTypeEqualityPredicate.apply(sourceSchema, targetSchema);
  }

  public static Option<Schema.Field> findNestedField(Schema schema, String fieldName) {
    return findNestedField(schema, fieldName.split("\\."), 0);
  }

  private static Option<Schema.Field> findNestedField(Schema schema, String[] fieldParts, int index) {
    if (schema.getType().equals(Schema.Type.UNION)) {
      Option<Schema.Field> notUnion = findNestedField(resolveNullableSchema(schema), fieldParts, index);
      if (!notUnion.isPresent()) {
        return Option.empty();
      }
      Schema.Field nu = notUnion.get();
      return Option.of(new Schema.Field(nu.name(), nu.schema(), nu.doc(), nu.defaultVal()));
    }
    if (fieldParts.length <= index) {
      return Option.empty();
    }

    Schema.Field foundField = schema.getField(fieldParts[index]);
    if (foundField == null) {
      return Option.empty();
    }

    if (index == fieldParts.length - 1) {
      return Option.of(new Schema.Field(foundField.name(), foundField.schema(), foundField.doc(), foundField.defaultVal()));
    }

    Schema foundSchema = foundField.schema();
    Option<Schema.Field> nestedPart = findNestedField(foundSchema, fieldParts, index + 1);
    if (!nestedPart.isPresent()) {
      return Option.empty();
    }
    return nestedPart;
  }

  public static Schema appendFieldsToSchemaDedupNested(Schema schema, List<Schema.Field> newFields) {
    return appendFieldsToSchemaBase(schema, newFields, true);
  }

  public static Schema mergeSchemas(Schema a, Schema b) {
    if (!a.getType().equals(Schema.Type.RECORD)) {
      return a;
    }
    List<Schema.Field> fields = new ArrayList<>();
    for (Schema.Field f : a.getFields()) {
      Schema.Field foundField = b.getField(f.name());
      fields.add(new Schema.Field(f.name(), foundField == null ? f.schema() : mergeSchemas(f.schema(), foundField.schema()),
          f.doc(), f.defaultVal()));
    }
    for (Schema.Field f : b.getFields()) {
      if (a.getField(f.name()) == null) {
        fields.add(new Schema.Field(f.name(), f.schema(), f.doc(), f.defaultVal()));
      }
    }

    Schema newSchema = Schema.createRecord(a.getName(), a.getDoc(), a.getNamespace(), a.isError());
    newSchema.setFields(fields);
    return newSchema;
  }

  /**
   * Appends provided new fields at the end of the given schema
   *
   * NOTE: No deduplication is made, this method simply appends fields at the end of the list
   *       of the source schema as is
   */
  public static Schema appendFieldsToSchema(Schema schema, List<Schema.Field> newFields) {
    return appendFieldsToSchemaBase(schema, newFields, false);
  }

  private static Schema appendFieldsToSchemaBase(Schema schema, List<Schema.Field> newFields, boolean dedupNested) {
    List<Schema.Field> fields = schema.getFields().stream()
        .map(field -> new Schema.Field(field.name(), field.schema(), field.doc(), field.defaultVal()))
        .collect(Collectors.toList());
    if (dedupNested) {
      for (Schema.Field f : newFields) {
        Schema.Field foundField = schema.getField(f.name());
        if (foundField != null) {
          fields.set(foundField.pos(), new Schema.Field(foundField.name(), mergeSchemas(foundField.schema(), f.schema()), foundField.doc(), foundField.defaultVal()));
        } else {
          fields.add(f);
        }
      }
    } else {
      fields.addAll(newFields);
    }

    Schema newSchema = Schema.createRecord(schema.getName(), schema.getDoc(), schema.getNamespace(), schema.isError());
    newSchema.setFields(fields);
    return newSchema;
  }

  /**
   * Passed in {@code Union} schema and will try to resolve the field with the {@code fieldSchemaFullName}
   * w/in the union returning its corresponding schema
   *
   * @param schema target schema to be inspected
   * @param fieldSchemaFullName target field-name to be looked up w/in the union
   * @return schema of the field w/in the union identified by the {@code fieldSchemaFullName}
   */
  public static Schema resolveUnionSchema(Schema schema, String fieldSchemaFullName) {
    if (schema.getType() != Schema.Type.UNION) {
      return schema;
    }

    List<Schema> innerTypes = schema.getTypes();
    if (innerTypes.size() == 2 && isNullable(schema)) {
      // this is a basic nullable field so handle it more efficiently
      return resolveNullableSchema(schema);
    }

    Schema nonNullType =
        innerTypes.stream()
            .filter(it -> it.getType() != Schema.Type.NULL && Objects.equals(it.getFullName(), fieldSchemaFullName))
            .findFirst()
            .orElse(null);

    if (nonNullType == null) {
      throw new AvroRuntimeException(
          String.format("Unsupported Avro UNION type %s: Only UNION of a null type and a non-null type is supported", schema));
    }

    return nonNullType;
  }

  /**
   * Returns true in case provided {@link Schema} is nullable (ie accepting null values),
   * returns false otherwise
   */
  public static boolean isNullable(Schema schema) {
    if (schema.getType() != Schema.Type.UNION) {
      return false;
    }

    List<Schema> innerTypes = schema.getTypes();
    return innerTypes.size() > 1 && innerTypes.stream().anyMatch(it -> it.getType() == Schema.Type.NULL);
  }

  /**
   * Resolves typical Avro's nullable schema definition: {@code Union(Schema.Type.NULL, <NonNullType>)},
   * decomposing union and returning the target non-null type
   */
  public static Schema resolveNullableSchema(Schema schema) {
    if (schema.getType() != Schema.Type.UNION) {
      return schema;
    }

    List<Schema> innerTypes = schema.getTypes();

    if (innerTypes.size() != 2) {
      throw new AvroRuntimeException(
          String.format("Unsupported Avro UNION type %s: Only UNION of a null type and a non-null type is supported", schema));
    }
    Schema firstInnerType = innerTypes.get(0);
    Schema secondInnerType = innerTypes.get(1);
    if ((firstInnerType.getType() != Schema.Type.NULL && secondInnerType.getType() != Schema.Type.NULL)
        || (firstInnerType.getType() == Schema.Type.NULL && secondInnerType.getType() == Schema.Type.NULL)) {
      throw new AvroRuntimeException(
          String.format("Unsupported Avro UNION type %s: Only UNION of a null type and a non-null type is supported", schema));
    }
    return firstInnerType.getType() == Schema.Type.NULL ? secondInnerType : firstInnerType;
  }

  /**
   * Creates schema following Avro's typical nullable schema definition: {@code Union(Schema.Type.NULL, <NonNullType>)},
   * wrapping around provided target non-null type
   */
  public static Schema createNullableSchema(Schema.Type avroType) {
    return createNullableSchema(Schema.create(avroType));
  }

  public static Schema createNullableSchema(Schema schema) {
    checkState(schema.getType() != Schema.Type.NULL);
    return Schema.createUnion(Schema.create(Schema.Type.NULL), schema);
  }

  /**
   * Returns true in case when schema contains the field w/ provided name
   */
  public static boolean containsFieldInSchema(Schema schema, String fieldName) {
    try {
      Schema.Field field = schema.getField(fieldName);
      return field != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Checks whether writer schema is compatible with table schema considering {@code AVRO_SCHEMA_VALIDATE_ENABLE}
   * and {@code SCHEMA_ALLOW_AUTO_EVOLUTION_COLUMN_DROP} options.
   * To avoid collision of {@code SCHEMA_ALLOW_AUTO_EVOLUTION_COLUMN_DROP} and {@code DROP_PARTITION_COLUMNS}
   * partition column names should be passed as {@code dropPartitionColNames}.
   * Passed empty set means {@code DROP_PARTITION_COLUMNS} is disabled.
   *
   * @param tableSchema the latest dataset schema
   * @param writerSchema writer schema
   * @param shouldValidate whether {@link AvroSchemaCompatibility} check being performed
   * @param allowProjection whether column dropping check being performed
   * @param dropPartitionColNames partition column names to being excluded from column dropping check
   * @throws SchemaCompatibilityException if writer schema is not compatible
   */
  public static void checkSchemaCompatible(
      Schema tableSchema,
      Schema writerSchema,
      boolean shouldValidate,
      boolean allowProjection,
      Set<String> dropPartitionColNames) throws SchemaCompatibilityException {

    String errorMessage = null;

    if (!allowProjection && !canProject(tableSchema, writerSchema, dropPartitionColNames)) {
      errorMessage = "Column dropping is not allowed";
    }

    // TODO(HUDI-4772) re-enable validations in case partition columns
    //                 being dropped from the data-file after fixing the write schema
    if (dropPartitionColNames.isEmpty() && shouldValidate && !isSchemaCompatible(tableSchema, writerSchema)) {
      errorMessage = "Failed schema compatibility check";
    }

    if (errorMessage != null) {
      String errorDetails = String.format(
          "%s\nwriterSchema: %s\ntableSchema: %s",
          errorMessage,
          writerSchema,
          tableSchema);
      throw new SchemaCompatibilityException(errorDetails);
    }
  }
}
