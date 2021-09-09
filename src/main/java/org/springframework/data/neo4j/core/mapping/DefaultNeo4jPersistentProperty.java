/*
 * Copyright 2011-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.neo4j.core.mapping;

import java.util.Optional;
import java.util.function.Function;

import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.model.AnnotationBasedPersistentProperty;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.data.neo4j.core.convert.ConvertWith;
import org.springframework.data.neo4j.core.convert.Neo4jPersistentPropertyConverter;
import org.springframework.data.neo4j.core.schema.CompositeProperty;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.data.util.Lazy;
import org.springframework.data.util.TypeInformation;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * @author Michael J. Simons
 * @since 6.0
 */
final class DefaultNeo4jPersistentProperty extends AnnotationBasedPersistentProperty<Neo4jPersistentProperty>
		implements Neo4jPersistentProperty {

	private final Lazy<String> graphPropertyName;
	/**
	 * A flag whether this is a writeable property: Something that ends up on a Neo4j node or relationship.
	 */
	private final Lazy<Boolean> isWritableProperty;
	/**
	 * A flag whether this domain property manifests itself as a relationship in Neo4j.
	 */
	private final Lazy<Boolean> isAssociation;

	private final Neo4jMappingContext mappingContext;

	private final Lazy<Neo4jPersistentPropertyConverter<?>> customConversion;

	/**
	 * Creates a new {@link AnnotationBasedPersistentProperty}.
	 *
	 * @param property         must not be {@literal null}.
	 * @param owner            must not be {@literal null}.
	 * @param simpleTypeHolder type holder
	 */
	DefaultNeo4jPersistentProperty(Property property, PersistentEntity<?, Neo4jPersistentProperty> owner,
			Neo4jMappingContext mappingContext, SimpleTypeHolder simpleTypeHolder) {

		super(property, owner, simpleTypeHolder);
		this.mappingContext = mappingContext;

		this.graphPropertyName = Lazy.of(this::computeGraphPropertyName);

		this.isWritableProperty = Lazy.of(() -> {
			Class<?> targetType = getActualType();
			return simpleTypeHolder.isSimpleType(targetType) // The driver can do this
				   || this.mappingContext.hasCustomWriteTarget(targetType) // Some converter in the context can do this
				   || isAnnotationPresent(ConvertWith.class) // An explicit converter can do this
				   || isComposite(); // Our composite converter can do this
		});

		this.isAssociation = Lazy.of(() -> {

			// Bail out early, this is pretty much explicit
			if (isAnnotationPresent(Relationship.class)) {
				return true;
			}
			return !(isWritableProperty.get() || isAnnotationPresent(TargetNode.class));
		});

		this.customConversion = Lazy.of(() -> {

			if (this.isEntity()) {
				return null;
			}

			return this.mappingContext.getOptionalCustomConversionsFor(this);
		});
	}

	@Override
	protected Association<Neo4jPersistentProperty> createAssociation() {

		Neo4jPersistentEntity<?> obverseOwner;

		boolean dynamicAssociation = this.isDynamicAssociation();

		Neo4jPersistentEntity<?> relationshipPropertiesClass = null;

		if (this.hasActualTypeAnnotation(RelationshipProperties.class)) {
			TypeInformation<?> type = getRelationshipPropertiesTargetType(getActualType());
			obverseOwner = this.mappingContext.getPersistentEntity(type);
			relationshipPropertiesClass = this.mappingContext.getPersistentEntity(getActualType());
		} else {
			Class<?> associationTargetType = this.getAssociationTargetType();
			obverseOwner = this.mappingContext.addPersistentEntity(ClassTypeInformation.from(associationTargetType)).orElse(null);
			Assert.notNull(obverseOwner, "Obverse owner could not be added.");
			if (dynamicAssociation) {

				TypeInformation<?> mapValueType = this.getTypeInformation().getMapValueType();

				boolean relationshipPropertiesCollection =
						this.mappingContext.getPersistentEntity(mapValueType.getActualType().getType())
								.isRelationshipPropertiesEntity();

				boolean relationshipPropertiesScalar =
						mapValueType.getType().isAnnotationPresent(RelationshipProperties.class);

				if (relationshipPropertiesCollection) {
					TypeInformation<?> type = getRelationshipPropertiesTargetType(mapValueType.getActualType().getType());
					obverseOwner = this.mappingContext.getPersistentEntity(type);
					relationshipPropertiesClass = this.mappingContext
							.getPersistentEntity(mapValueType.getComponentType().getType());

				} else if (relationshipPropertiesScalar) {
					relationshipPropertiesClass = this.mappingContext.getPersistentEntity(mapValueType.getType());
				}
			}
		}

		Relationship relationship = this.findAnnotation(Relationship.class);

		String type;
		if (relationship != null && StringUtils.hasText(relationship.type())) {
			type = relationship.type();
		} else {
			type = deriveRelationshipType(this.getName());
		}

		Relationship.Direction direction = relationship != null
				? relationship.direction()
				: Relationship.Direction.OUTGOING;

		// Try to determine if there is a relationship definition that expresses logically the same relationship
		// on the other end.
		Optional<RelationshipDescription> obverseRelationshipDescription = obverseOwner.getRelationships().stream()
				.filter(rel -> rel.getType().equals(type)
						&& rel.getTarget().equals(this.getOwner())
						&& rel.getDirection() == direction.opposite()).findFirst();

		DefaultRelationshipDescription relationshipDescription = new DefaultRelationshipDescription(this,
				obverseRelationshipDescription.orElse(null), type, dynamicAssociation, (NodeDescription<?>) getOwner(),
				this.getName(), obverseOwner, direction, relationshipPropertiesClass);

		// Update the previous found, if any, relationship with the newly created one as its counterpart.
		obverseRelationshipDescription
				.ifPresent(observeRelationship -> observeRelationship.setRelationshipObverse(relationshipDescription));

		return relationshipDescription;
	}

	@NonNull
	private TypeInformation<?> getRelationshipPropertiesTargetType(Class<?> relationshipPropertiesType) {
		return this.mappingContext.addPersistentEntity(ClassTypeInformation.from(relationshipPropertiesType))
				.map(entity -> entity.getPersistentProperty(TargetNode.class))
				.map(PersistentProperty::getTypeInformation)
				.orElseThrow(
						() -> new MappingException("Missing @TargetNode declaration in " + relationshipPropertiesType));
	}

	@Override
	public Class<?> getAssociationTargetType() {

		if (isDynamicOneToManyAssociation()) {
			TypeInformation<?> actualType = getTypeInformation().getRequiredActualType();
			return actualType.getRequiredComponentType().getType();
		} else {
			return getActualType();
		}
	}

	@Override
	public boolean isAssociation() {

		return this.isAssociation.or(false).get();
	}

	@Override
	public boolean isEntity() {
		return super.isEntity() && !isWritableProperty.get();
	}

	@Override
	public boolean isEntityWithRelationshipProperties() {
		return isEntity() && ((Neo4jPersistentEntity<?>) getOwner()).isRelationshipPropertiesEntity();
	}

	private static Function<Object, Value> nullSafeWrite(Function<Object, Value> delegate) {
		return source -> source == null ? Values.NULL : delegate.apply(source);
	}

	@Override
	public Function<Object, Value> getOptionalWritingConverter() {
		return customConversion.getOptional()
				.map(Neo4jPersistentPropertyConverter.class::cast)
				.map(c -> {
					@SuppressWarnings("unchecked")
					Function<Object, Value> originalConversion = c::write;
					return this.isComposite() ? originalConversion : nullSafeWrite(originalConversion);
				})
				.orElse(null);
	}

	private static Function<Value, Object> nullSafeRead(Function<Value, Object> delegate) {
		return source -> source == null || source.isNull() ? null : delegate.apply(source);
	}

	@Override
	public Function<Value, Object> getOptionalReadingConverter() {
		return customConversion.getOptional().map(c -> nullSafeRead(c::read)).orElse(null);
	}

	/**
	 * Computes the target name of this property.
	 *
	 * @return A property on a node or {@literal null} if this property describes an association.
	 */
	@Nullable
	private String computeGraphPropertyName() {

		if (this.isAssociation()) {
			return null;
		}

		org.springframework.data.neo4j.core.schema.Property propertyAnnotation = this
				.findAnnotation(org.springframework.data.neo4j.core.schema.Property.class);

		String targetName = this.getName();
		if (propertyAnnotation != null && !propertyAnnotation.name().isEmpty()
				&& propertyAnnotation.name().trim().length() != 0) {
			targetName = propertyAnnotation.name().trim();
		}

		return targetName;
	}

	@Override
	public String getFieldName() {
		return this.getName();
	}

	@Override
	public String getPropertyName() {

		String propertyName = this.graphPropertyName.getNullable();
		if (propertyName == null) {
			throw new MappingException("The attribute '" + this.getFieldName() + "' is not mapped to a Graph property!");
		}

		return propertyName;
	}

	@Override
	public boolean isInternalIdProperty() {

		return this.isIdProperty() && ((Neo4jPersistentEntity<?>) this.getOwner()).isUsingInternalIds();
	}

	@Override
	public boolean isRelationship() {

		return isAssociation();
	}

	@Override
	public boolean isComposite() {

		return isAnnotationPresent(CompositeProperty.class);
	}

	static String deriveRelationshipType(String name) {

		Assert.hasText(name, "The name to derive the type from is required.");

		StringBuilder sb = new StringBuilder();

		int codePoint;
		int previousIndex = 0;
		int i = 0;
		while (i < name.length()) {
			codePoint = name.codePointAt(i);
			if (Character.isLowerCase(codePoint)) {
				if (i > 0 && !Character.isLetter(name.codePointAt(previousIndex))) {
					sb.append("_");
				}
				codePoint = Character.toUpperCase(codePoint);
			} else if (sb.length() > 0) {
				sb.append("_");
			}
			sb.append(Character.toChars(codePoint));
			previousIndex = i;
			i += Character.charCount(codePoint);
		}
		return sb.toString();
	}

	@Override
	public boolean isReadOnly() {

		Class<org.springframework.data.neo4j.core.schema.Property> typeOfAnnotation = org.springframework.data.neo4j.core.schema.Property.class;
		return isAnnotationPresent(ReadOnlyProperty.class) || (isAnnotationPresent(typeOfAnnotation) && getRequiredAnnotation(typeOfAnnotation).readOnly());
	}
}
