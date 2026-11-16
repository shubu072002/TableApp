package com.fci.tableapp.model;

import java.util.List;

public record TableDetails(TableInfo table, List<ColumnInfo> columns, long rowCount) {
}
