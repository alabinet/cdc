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
