​https://debezium.io/documentation/reference/stable/connectors/sqlserver.html

https://docs.hazelcast.org/docs/5.5.0/javadoc/com/hazelcast/jet/cdc/DebeziumCdcSources.html

https://docs.hazelcast.com/hazelcast/5.5/pipelines/cdc-join#step-7-start-hazelcast

----------------------------------------------------------------------------------------------------------

version: '3.8'
services:

  hazelcast:
    image: hazelcast/hazelcast:5.5.6
    ports:
      - "5701:5701"
    environment:
      - HZ_NETWORK_PUBLICADDRESS=hazelcast:5701
      - HZ_CLUSTERNAME=dev

  management-center:
    image: hazelcast/management-center:5.8.0
    ports:
      - "8080:8080"
    environment:
      - MC_DEFAULT_CLUSTER=dev
      - MC_DEFAULT_CLUSTER_MEMBERS=hazelcast
    depends_on:
      - hazelcast

  sqlserver:
    image: mcr.microsoft.com/mssql/server:2019-latest
    container_name: sqlserver
    environment:
      ACCEPT_EULA: 'Y'
      SA_PASSWORD: 'Password123'
      MSSQL_PID: 'Developer'
    ports:
      - 1433:1433
    volumes:
      - ./init-sqlserver.sh:/init-sqlserver.sh
    entrypoint:
      - "/bin/bash"
      - "-c"
      - "/init-sqlserver.sh & /opt/mssql/bin/sqlservr"

  mongo:
    image: mongo:6
    container_name: mongo
    ports:
      - 27017:27017

  maven:
    image: maven:3.9.6-eclipse-temurin-17
    container_name: maven-build
    working_dir: /usr/src/app
    volumes:
      - ./:/usr/src/app
    command: mvn clean package

  app:
    image: eclipse-temurin:17
    container_name: java-runner
    depends_on:
      - hazelcast
      - management-center
      - maven
      - sqlserver
      - mongo
    working_dir: /usr/src/app
    volumes:
      - ./:/usr/src/app
    command: >
      bash -c "sleep 15 && java -jar target/hazelcast-mongo-cdc-1.0-SNAPSHOT-shaded.jar"


-----------------------------------------------------------------------------------------------------------------------------

package cdc;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

//para versao Community
import com.hazelcast.jet.cdc.CdcSinks;
import com.hazelcast.jet.cdc.ChangeRecord;
import com.hazelcast.jet.cdc.DebeziumCdcSources;
import com.hazelcast.jet.mongodb.MongoSinks;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.StreamSource;
import com.hazelcast.jet.pipeline.StreamStage;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.Jet;

import org.bson.Document;


public class CdcSqlServerToMongo {
  public static void main(String[] args) {

    JetInstance jet = Jet.newJetInstance();
   
    Pipeline p = Pipeline.create();

   
    StreamSource<String> src = DebeziumCdcSources.debezium("sqlserver-cdc", cfg -> {
      cfg.setProperty("connector.class", "io.debezium.connector.sqlserver.SqlServerConnector");
      cfg.setProperty("database.hostname", "sqlserver");
      cfg.setProperty("database.port", "1433");
      cfg.setProperty("database.user", "sa");
      cfg.setProperty("database.password", "Password123");
      cfg.setProperty("database.dbname", "ClientesDB");
      cfg.setProperty("database.server.name", "sqlserver");
      cfg.setProperty("table.include.list", "dbo.Clientes");
    });


    p.readFrom(src)
     .withoutTimestamps()
     .map(json -> {
       Document payload = Document.parse(json).get("payload", Document.class);
       Document after = payload.get("after", Document.class);
       if (after == null) return null;
       return new Document("_id", after.get("id"))
           .append("nome", after.getString("nome"))
           .append("email", after.getString("email"));
     })
     .filter(d -> d != null)
     .writeTo(
       MongoSinks.builder(Document.class, () ->
         com.mongodb.client.MongoClients.create("mongodb://mongo:27017"))
       .into("cdc_data", "clientes")
       .identifyDocumentBy("_id", d -> d.get("_id"))
       .build()
     );

     
    jet.newJob(p);
  }
}

-------------------------------------------------------------------------------------------

#!/bin/bash
sleep 15
/opt/mssql-tools/bin/sqlcmd -S localhost -U SA -P Password123 <<EOF
IF DB_ID('ClientesDB') IS NULL
  CREATE DATABASE ClientesDB;
GO
USE ClientesDB;
IF NOT EXISTS (SELECT * FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name='dbo' AND t.name='Clientes')
BEGIN
  CREATE TABLE dbo.Clientes (
    id INT PRIMARY KEY,
    nome NVARCHAR(100),
    email NVARCHAR(100)
  );
END
GO
IF NOT EXISTS (SELECT * FROM sys.databases WHERE is_cdc_enabled=1 AND name='ClientesDB')
BEGIN
  EXEC sys.sp_cdc_enable_db;
END
GO
IF NOT EXISTS (SELECT * FROM cdc.change_tables WHERE source_object_id = OBJECT_ID('dbo.Clientes'))
BEGIN
  EXEC sys.sp_cdc_enable_table @source_schema=N'dbo', @source_name=N'Clientes', @role_name=NULL;
END
GO
EOF

------------------------------------------------------------------------------------------------------------------------------

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>cdc</groupId>
  <artifactId>hazelcast-mongo-cdc</artifactId>
  <version>1.0-SNAPSHOT</version>
  <properties><java.version>11</java.version></properties>
  <dependencies>
    <dependency>
      <groupId>com.hazelcast</groupId>
      <artifactId>hazelcast</artifactId>
      <version>5.5.6</version>
    </dependency>
    <dependency>
      <groupId>com.hazelcast.jet</groupId>
      <artifactId>hazelcast-cdc-debezium</artifactId>
      <version>5.5.6</version>
    </dependency>
    <dependency>
      <groupId>com.hazelcast.jet</groupId>
      <artifactId>hazelcast-jet</artifactId>
      <version>4.5</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
      <version>2.18.0</version>
    </dependency>
    <dependency>
      <groupId>io.debezium</groupId>
      <artifactId>debezium-connector-sqlserver</artifactId>
      <version>2.6.1.Final</version>
    </dependency>
    <dependency>
      <groupId>com.hazelcast.jet</groupId>
      <artifactId>hazelcast-jet-mongodb</artifactId>
      <version>5.5.0</version>
    </dependency>
    <dependency>
      <groupId>org.mongodb</groupId>
      <artifactId>mongodb-driver-sync</artifactId>
      <version>4.11.0</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.4.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>cdc.CdcSqlServerToMongo</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>

