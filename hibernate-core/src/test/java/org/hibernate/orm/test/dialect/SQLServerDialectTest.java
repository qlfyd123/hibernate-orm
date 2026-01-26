/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.dialect;

import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;
import org.hibernate.type.spi.TypeConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SQLServerDialectTest {

	@Test
	@JiraKey( "HHH-20092" )
	public void testJsonMapToNvarchar() {
		SQLServerDialect dialect = new SQLServerDialect();
		TypeConfiguration typeConfiguration = new TypeConfiguration();
		DdlTypeRegistry ddlTypeRegistry = typeConfiguration.getDdlTypeRegistry();

		TypeContributions typeContributions = mock(TypeContributions.class);
		when(typeContributions.getTypeConfiguration()).thenReturn(typeConfiguration);

		ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);

		dialect.contributeTypes(typeContributions, serviceRegistry);

		String typeName = ddlTypeRegistry.getTypeName(SqlTypes.JSON, dialect);
		assertEquals("nvarchar(max)", typeName);
	}
}
