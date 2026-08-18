/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.collection.detached;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKey;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;
import org.hibernate.Hibernate;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DomainModel(annotatedClasses = {
		MergeDetachedCollectionAfterBulkDeleteTest.Car.class,
		MergeDetachedCollectionAfterBulkDeleteTest.Bag.class,
		MergeDetachedCollectionAfterBulkDeleteTest.ListOwner.class,
		MergeDetachedCollectionAfterBulkDeleteTest.ListItem.class,
		MergeDetachedCollectionAfterBulkDeleteTest.BagOwner.class,
		MergeDetachedCollectionAfterBulkDeleteTest.BagItem.class,
		MergeDetachedCollectionAfterBulkDeleteTest.MapOwner.class,
		MergeDetachedCollectionAfterBulkDeleteTest.MapItem.class
})
@SessionFactory
public class MergeDetachedCollectionAfterBulkDeleteTest {

	@AfterEach
	public void clean(SessionFactoryScope scope) {
		scope.dropData();
	}

	@AfterAll
	public void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().drop( true );
	}

	@Test
	public void testMergeDirtyDetachedCollectionAfterBulkDelete(SessionFactoryScope scope) {
		final Car car = createCarWithTwoBags( scope );
		final PersistentCollection<?> detachedTrunk = (PersistentCollection<?>) car.getTrunk();
		assertEquals( 2, car.getTrunk().size() );
		assertEquals( 2, ((Map<?, ?>) detachedTrunk.getStoredSnapshot()).size() );

		final String deletedBagId = car.getTrunk().iterator().next().getId();

		scope.inTransaction( entityManager -> {
			final CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
			final CriteriaDelete<Bag> delete = criteriaBuilder.createCriteriaDelete( Bag.class );
			final Root<Bag> bag = delete.from( Bag.class );
			delete.where( criteriaBuilder.equal( bag.get( BaseEntity.PROPERTY_ID ), deletedBagId ) );

			assertEquals( 1, entityManager.createStatement( delete ).execute() );
		} );

		assertBagWasNotRecreated( scope, car.getId(), deletedBagId );

		car.getTrunk().clear();
		assertTrue( car.getTrunk().isEmpty() );
		assertTrue( detachedTrunk.isDirty() );
		assertEquals( 2, ((Map<?, ?>) detachedTrunk.getStoredSnapshot()).size() );

		// Regression test: merging the detached car must not fail after one of the
		// collection elements was deleted independently with a bulk operation.
		scope.inTransaction( entityManager -> {
			assertNull( entityManager.find( Bag.class, deletedBagId ) );
			final Car managedCar = entityManager.find( Car.class, car.getId() );
			assertEquals( 1, managedCar.getTrunk().size() );

			final Car mergedCar = entityManager.merge( car );
			assertSame( managedCar, mergedCar );
			assertTrue( mergedCar.getTrunk().isEmpty() );
		} );

		assertBagWasNotRecreated( scope, car.getId(), deletedBagId );
	}

	@Test
	public void testMergeDetachedCollectionAfterRemovingDeletedElement(SessionFactoryScope scope) {
		final Car car = createCarWithTwoBags( scope );
		final Bag deletedBag = car.getTrunk().iterator().next();
		final String deletedBagId = deletedBag.getId();
		final String survivingBagId = car.getTrunk().stream()
				.filter( bag -> bag != deletedBag )
				.findFirst()
				.orElseThrow()
				.getId();

		bulkDelete( scope, Bag.class, deletedBagId );
		assertTrue( car.getTrunk().remove( deletedBag ) );

		scope.inTransaction( entityManager -> {
			final Car mergedCar = entityManager.merge( car );
			assertEquals( 1, mergedCar.getTrunk().size() );
			assertEquals( survivingBagId, mergedCar.getTrunk().iterator().next().getId() );
		} );

		assertBagWasNotRecreated( scope, car.getId(), deletedBagId );
	}

	@Test
	public void testMergeDetachedCollectionWithDeletedCurrentElement(SessionFactoryScope scope) {
		final Car car = createCarWithTwoBags( scope );
		final Bag deletedBag = car.getTrunk().iterator().next();


		final String deletedBagId = deletedBag.getId();
		final Bag survivingBag = car.getTrunk().stream()
				.filter( bag -> bag != deletedBag )
				.findFirst()
				.orElseThrow();

		bulkDelete( scope, Bag.class, deletedBagId );
		assertTrue( car.getTrunk().remove( survivingBag ) );
		assertEquals( Set.of( deletedBag ), car.getTrunk() );

		scope.inTransaction( entityManager -> {
			final Car mergedCar = entityManager.merge( car );
			assertTrue( mergedCar.getTrunk().isEmpty() );
		} );

		assertBagWasNotRecreated( scope, car.getId(), deletedBagId );
	}

	@Test
	public void testMergeDetachedListAfterBulkDelete(SessionFactoryScope scope) {
		final ListOwner owner = createListOwnerWithTwoItems( scope );
		final String deletedItemId = owner.getItems().get( 0 ).getId();

		bulkDelete( scope, ListItem.class, deletedItemId );
		owner.getItems().clear();

		scope.inTransaction( entityManager -> assertTrue( entityManager.merge( owner ).getItems().isEmpty() ) );
		scope.inTransaction( entityManager -> assertNull( entityManager.find( ListItem.class, deletedItemId ) ) );
	}

	@Test
	public void testMergeDetachedBagAfterBulkDelete(SessionFactoryScope scope) {
		final BagOwner owner = createBagOwnerWithTwoItems( scope );
		final String deletedItemId = owner.getItems().get( 0 ).getId();

		bulkDelete( scope, BagItem.class, deletedItemId );
		owner.getItems().clear();

		scope.inTransaction( entityManager -> assertTrue( entityManager.merge( owner ).getItems().isEmpty() ) );
		scope.inTransaction( entityManager -> assertNull( entityManager.find( BagItem.class, deletedItemId ) ) );
	}

	@Test
	public void testMergeDetachedMapAfterBulkDelete(SessionFactoryScope scope) {
		final MapOwner owner = createMapOwnerWithTwoItems( scope );
		final MapItem deletedItem = owner.getItems().values().iterator().next();
		final String deletedItemId = deletedItem.getId();

		bulkDelete( scope, MapItem.class, deletedItemId );

		scope.inTransaction( entityManager -> {
			final MapOwner mergedOwner = entityManager.merge( owner );
			assertEquals( 1, mergedOwner.getItems().size() );
			assertFalse( mergedOwner.getItems().containsKey( deletedItem.getMapKey() ) );
		} );
		scope.inTransaction( entityManager -> assertNull( entityManager.find( MapItem.class, deletedItemId ) ) );
	}

	private <T extends BaseEntity> void bulkDelete(SessionFactoryScope scope, Class<T> entityType, String id) {
		scope.inTransaction( entityManager -> {
			final CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
			final CriteriaDelete<T> delete = criteriaBuilder.createCriteriaDelete( entityType );
			final Root<T> root = delete.from( entityType );
			delete.where( criteriaBuilder.equal( root.get( BaseEntity.PROPERTY_ID ), id ) );
			assertEquals( 1, entityManager.createStatement( delete ).execute() );
		} );
	}

	private void assertBagWasNotRecreated(SessionFactoryScope scope, String carId, String deletedBagId) {
		scope.inTransaction( entityManager -> {
			assertNull( entityManager.find( Bag.class, deletedBagId ) );
			assertEquals( 1, entityManager.find( Car.class, carId ).getTrunk().size() );
		} );
	}

	@Test
	public void testMergeCleanDetachedCollectionAfterBulkDelete(SessionFactoryScope scope) {
		final Car car = createCarWithTwoBags( scope );
		final PersistentCollection<?> detachedTrunk = (PersistentCollection<?>) car.getTrunk();
		assertEquals( 2, car.getTrunk().size() );
		assertEquals( 2, ((Map<?, ?>) detachedTrunk.getStoredSnapshot()).size() );

		final String deletedBagId = car.getTrunk().iterator().next().getId();

		scope.inTransaction( entityManager -> {
			final CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
			final CriteriaDelete<Bag> delete = criteriaBuilder.createCriteriaDelete( Bag.class );
			final Root<Bag> bag = delete.from( Bag.class );
			delete.where( criteriaBuilder.equal( bag.get( BaseEntity.PROPERTY_ID ), deletedBagId ) );

			assertEquals( 1, entityManager.createStatement( delete ).execute() );
		} );
		assertFalse( detachedTrunk.isDirty() );

		scope.inTransaction( entityManager -> {
			assertNull( entityManager.find( Bag.class, deletedBagId ) );
			final Car managedCar = entityManager.find( Car.class, car.getId() );
			assertEquals( 1, managedCar.getTrunk().size() );

			final Car mergedCar = entityManager.merge( car );
			assertSame( managedCar, mergedCar );
			assertEquals( 1, mergedCar.getTrunk().size() );
			assertFalse( mergedCar.getTrunk().stream().anyMatch( bag -> bag.getId().equals( deletedBagId ) ) );
		} );

		assertBagWasNotRecreated( scope, car.getId(), deletedBagId );
	}

	private Car createCarWithTwoBags(SessionFactoryScope scope) {
		final Car car = new Car();
		scope.inTransaction( entityManager -> entityManager.persist( car ) );

		for ( int i = 0; i < 2; i++ ) {
			final Bag bag = new Bag();
			bag.setCar( car );
			scope.inTransaction( entityManager -> entityManager.persist( bag ) );
		}

		return scope.fromTransaction( entityManager -> {
			final Car loadedCar = entityManager.find( Car.class, car.getId() );
			Hibernate.initialize( loadedCar.getTrunk() );
			return loadedCar;
		} );
	}

	private ListOwner createListOwnerWithTwoItems(SessionFactoryScope scope) {
		final ListOwner owner = new ListOwner();
		scope.inTransaction( entityManager -> {
			entityManager.persist( owner );
			for ( int i = 0; i < 2; i++ ) {
				final ListItem item = new ListItem();
				item.setOwner( owner );
				owner.getItems().add( item );
				entityManager.persist( item );
			}
		} );
		return scope.fromTransaction( entityManager -> {
			final ListOwner loadedOwner = entityManager.find( ListOwner.class, owner.getId() );
			Hibernate.initialize( loadedOwner.getItems() );
			return loadedOwner;
		} );
	}

	private BagOwner createBagOwnerWithTwoItems(SessionFactoryScope scope) {
		final BagOwner owner = new BagOwner();
		scope.inTransaction( entityManager -> {
			entityManager.persist( owner );
			for ( int i = 0; i < 2; i++ ) {
				final BagItem item = new BagItem();
				item.setOwner( owner );
				owner.getItems().add( item );
				entityManager.persist( item );
			}
		} );
		return scope.fromTransaction( entityManager -> {
			final BagOwner loadedOwner = entityManager.find( BagOwner.class, owner.getId() );
			Hibernate.initialize( loadedOwner.getItems() );
			return loadedOwner;
		} );
	}

	private MapOwner createMapOwnerWithTwoItems(SessionFactoryScope scope) {
		final MapOwner owner = new MapOwner();
		scope.inTransaction( entityManager -> {
			entityManager.persist( owner );
			for ( int i = 0; i < 2; i++ ) {
				final MapItem item = new MapItem();
				item.setOwner( owner );
				item.setMapKey( "item-" + i );
				owner.getItems().put( item.getMapKey(), item );
				entityManager.persist( item );
			}
		} );
		return scope.fromTransaction( entityManager -> {
			final MapOwner loadedOwner = entityManager.find( MapOwner.class, owner.getId() );
			Hibernate.initialize( loadedOwner.getItems() );
			return loadedOwner;
		} );
	}

	@MappedSuperclass
	public abstract static class BaseEntity {
		public static final String PROPERTY_ID = "id";

		private String id = UUID.randomUUID().toString();

		@Id
		@Access(AccessType.PROPERTY)
		@Column(name = PROPERTY_ID, length = 36, nullable = false)
		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		@Override
		public boolean equals(Object object) {
			if ( this == object ) {
				return true;
			}
			if ( !(object instanceof BaseEntity) ) {
				return false;
			}
			return getId().equals( ((BaseEntity) object).getId() );
		}

		@Override
		public int hashCode() {
			return getId().hashCode();
		}
	}

	@Entity(name = "Car")
	public static class Car extends BaseEntity {
		@OneToMany(fetch = FetchType.LAZY, mappedBy = "car")
		private Set<Bag> trunk = new HashSet<>();

		public Set<Bag> getTrunk() {
			return trunk;
		}
	}

	@Entity(name = "Bag")
	public static class Bag extends BaseEntity {
		@ManyToOne(fetch = FetchType.LAZY)
		@Fetch(FetchMode.SELECT)
		@JoinColumn(name = "car")
		private Car car;

		public Car getCar() {
			return car;
		}

		public void setCar(Car car) {
			this.car = car;
		}
	}

	@Entity(name = "ListOwner")
	public static class ListOwner extends BaseEntity {
		@OneToMany(fetch = FetchType.LAZY, mappedBy = "owner")
		@OrderColumn(name = "item_position")
		private List<ListItem> items = new ArrayList<>();

		public List<ListItem> getItems() {
			return items;
		}
	}

	@Entity(name = "ListItem")
	public static class ListItem extends BaseEntity {
		@ManyToOne(fetch = FetchType.LAZY)
		private ListOwner owner;

		public void setOwner(ListOwner owner) {
			this.owner = owner;
		}
	}

	@Entity(name = "BagOwner")
	public static class BagOwner extends BaseEntity {
		@OneToMany(fetch = FetchType.LAZY, mappedBy = "owner")
		@org.hibernate.annotations.Bag
		private List<BagItem> items = new ArrayList<>();

		public List<BagItem> getItems() {
			return items;
		}
	}

	@Entity(name = "BagItem")
	public static class BagItem extends BaseEntity {
		@ManyToOne(fetch = FetchType.LAZY)
		private BagOwner owner;

		public void setOwner(BagOwner owner) {
			this.owner = owner;
		}
	}

	@Entity(name = "MapOwner")
	public static class MapOwner extends BaseEntity {
		@OneToMany(fetch = FetchType.LAZY, mappedBy = "owner")
		@MapKey(name = "mapKey")
		private Map<String, MapItem> items = new HashMap<>();

		public Map<String, MapItem> getItems() {
			return items;
		}
	}

	@Entity(name = "MapItem")
	public static class MapItem extends BaseEntity {
		@Column(name = "map_key", nullable = false)
		private String mapKey;

		@ManyToOne(fetch = FetchType.LAZY)
		private MapOwner owner;

		public String getMapKey() {
			return mapKey;
		}

		public void setMapKey(String mapKey) {
			this.mapKey = mapKey;
		}

		public void setOwner(MapOwner owner) {
			this.owner = owner;
		}
	}
}
