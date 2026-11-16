package com.fci.tableapp.service;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Writes every row of a result set to an output stream in one file format. */
public interface TableExporter {

    String extension();

    String contentType();

    void write(ResultSet rs, String tableName, OutputStream out) throws SQLException, IOException;
}
