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
