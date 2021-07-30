package org.jetbrains.exposed.sql.vendors

import org.jetbrains.exposed.exceptions.throwUnsupportedException
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.GroupConcat
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.Sequence
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.append
import org.jetbrains.exposed.sql.appendTo
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.*

internal object PostgreSQLDataTypeProvider : DataTypeProvider() {
    override fun byteType(): String = "SMALLINT"
    override fun integerAutoincType(): String = "SERIAL"
    override fun longAutoincType(): String = "BIGSERIAL"
    override fun uuidType(): String = "uuid"
    override fun binaryType(): String = "bytea"
    override fun binaryType(length: Int): String {
        exposedLogger.warn("The length of the binary column is not required.")
        return binaryType()
    }

    override fun blobType(): String = "bytea"
    override fun uuidToDB(value: UUID): Any = value
    override fun dateTimeType(): String = "TIMESTAMP"
    override fun ubyteType(): String = "SMALLINT"
}

internal object PostgreSQLFunctionProvider : FunctionProvider() {

    override fun nextVal(seq: Sequence, builder: QueryBuilder): Unit = builder {
        append("NEXTVAL('", seq.identifier, "')")
    }

    override fun <T : String?> groupConcat(expr: GroupConcat<T>, queryBuilder: QueryBuilder) {
        val tr = TransactionManager.current()
        return when {
            expr.orderBy.isNotEmpty() -> tr.throwUnsupportedException("PostgreSQL doesn't support ORDER BY in STRING_AGG function.")
            expr.distinct -> tr.throwUnsupportedException("PostgreSQL doesn't support DISTINCT in STRING_AGG function.")
            expr.separator == null -> tr.throwUnsupportedException("PostgreSQL requires explicit separator in STRING_AGG function.")
            else -> queryBuilder { append("STRING_AGG(", expr.expr, ", '", expr.separator, "')") }
        }
    }

    override fun <T : String?> regexp(
        expr1: Expression<T>,
        pattern: Expression<String>,
        caseSensitive: Boolean,
        queryBuilder: QueryBuilder
    ): Unit = queryBuilder {
        append(expr1)
        if (caseSensitive) {
            append(" ~ ")
        } else {
            append(" ~* ")
        }
        append(pattern)
    }

    override fun <T> year(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("Extract(YEAR FROM ")
        append(expr)
        append(")")
    }

    override fun <T> month(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("Extract(MONTH FROM ")
        append(expr)
        append(")")
    }

    override fun <T> day(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("Extract(DAY FROM ")
        append(expr)
        append(")")
    }

    override fun <T> hour(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("Extract(HOUR FROM ")
        append(expr)
        append(")")
    }

    override fun <T> minute(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("Extract(MINUTE FROM ")
        append(expr)
        append(")")
    }

    override fun <T> second(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("Extract(SECOND FROM ")
        append(expr)
        append(")")
    }

    private const val onConflictIgnore = "ON CONFLICT DO NOTHING"

    override fun insert(
        ignore: Boolean,
        table: Table,
        columns: List<Column<*>>,
        expr: String,
        comment: String?,
        transaction: Transaction
    ): String {
        return if (ignore) {
            var tmp = "${super.insert(false, table, columns, expr, null, transaction)} $onConflictIgnore"
            comment?.let { tmp += " /* $it */ " }
            tmp
        } else {
            super.insert(false, table, columns, expr, comment, transaction)
        }
    }

    override fun update(
        target: Table,
        columnsAndValues: List<Pair<Column<*>, Any?>>,
        limit: Int?,
        where: Op<Boolean>?,
        comment: String?,
        transaction: Transaction
    ): String {
        if (limit != null) {
            transaction.throwUnsupportedException("PostgreSQL doesn't support LIMIT in UPDATE clause.")
        }
        return super.update(target, columnsAndValues, limit, where, comment, transaction)
    }

    override fun update(
        targets: Join,
        columnsAndValues: List<Pair<Column<*>, Any?>>,
        limit: Int?,
        where: Op<Boolean>?,
        comment: String?,
        transaction: Transaction
    ): String = with(QueryBuilder(true)) {
        if (limit != null) {
            transaction.throwUnsupportedException("PostgreSQL doesn't support LIMIT in UPDATE clause.")
        }
        val tableToUpdate = columnsAndValues.map { it.first.table }.distinct().singleOrNull()
            ?: transaction.throwUnsupportedException("PostgreSQL supports a join updates with a single table columns to update.")
        if (targets.joinParts.any { it.joinType != JoinType.INNER }) {
            exposedLogger.warn("All tables in UPDATE statement will be joined with inner join")
        }
        +"UPDATE "
        tableToUpdate.describe(transaction, this)
        +" SET "
        columnsAndValues.appendTo(this) { (col, value) ->
            append("${transaction.identity(col)}=")
            registerArgument(col, value)
        }
        +" FROM "
        if (targets.table != tableToUpdate)
            targets.table.describe(transaction, this)

        targets.joinParts.appendTo(this, ",") {
            if (it.joinPart != tableToUpdate)
                it.joinPart.describe(transaction, this)
        }
        +" WHERE "
        targets.joinParts.appendTo(this, " AND ") {
            it.appendConditions(this)
        }
        where?.let {
            +" AND "
            +it
        }
        comment?.let { +" /* $it */ " }
        toString()
    }

    override fun replace(
        table: Table,
        data: List<Pair<Column<*>, Any?>>,
        comment: String?,
        transaction: Transaction
    ): String {
        val builder = QueryBuilder(true)
        val sql = if (data.isEmpty()) {
            ""
        } else {
            data.appendTo(builder, prefix = "VALUES (", postfix = ")") { (col, value) -> registerArgument(col, value) }
                .toString()
        }

        val columns = data.map { it.first }

        val def = super.insert(false, table, columns, sql, comment, transaction)

        val uniqueCols = table.primaryKey?.columns
        if (uniqueCols.isNullOrEmpty()) {
            transaction.throwUnsupportedException("PostgreSQL replace table must supply at least one primary key.")
        }
        val conflictKey = uniqueCols.joinToString { transaction.identity(it) }
        return def + "ON CONFLICT ($conflictKey) DO UPDATE SET " + columns.joinToString {
            "${transaction.identity(it)}=EXCLUDED.${
                transaction.identity(
                    it
                )
            }"
        }
    }

    override fun delete(
        ignore: Boolean,
        table: Table,
        where: String?,
        limit: Int?,
        transaction: Transaction
    ): String {
        if (limit != null) {
            transaction.throwUnsupportedException("PostgreSQL doesn't support LIMIT in DELETE clause.")
        }
        return super.delete(ignore, table, where, limit, transaction)
    }
}

/**
 * PostgreSQL dialect implementation.
 */
open class PostgreSQLDialect : VendorDialect(dialectName, PostgreSQLDataTypeProvider, PostgreSQLFunctionProvider) {
    override fun isAllowedAsColumnDefault(e: Expression<*>): Boolean = true

    override fun modifyColumn(column: Column<*>): String = buildString {
        val colName = TransactionManager.current().identity(column)
        append("ALTER COLUMN $colName TYPE ${column.columnType.sqlType()},")
        append("ALTER COLUMN $colName ")
        if (column.columnType.nullable)
            append("DROP ")
        else
            append("SET ")
        append("NOT NULL")
        column.dbDefaultValue?.let {
            append(", ALTER COLUMN $colName SET DEFAULT ${PostgreSQLDataTypeProvider.processForDefaultValue(it)}")
        }
    }

    override fun createDatabase(name: String): String = "CREATE DATABASE ${name.inProperCase()}"

    override fun dropDatabase(name: String): String = "DROP DATABASE ${name.inProperCase()}"

    override fun setSchema(schema: Schema): String = "SET search_path TO ${schema.identifier}"

    override fun createIndexWithType(name: String, table: String, columns: String, type: String): String {
        return "CREATE INDEX $name ON $table USING $type $columns"
    }

    companion object {
        /** PostgreSQL dialect name */
        const val dialectName: String = "postgresql"
    }
}

/**
 * PostgreSQL dialect implementation using the pgjdbc-ng jdbc driver.
 *
 * The driver accepts basic URLs in the following format : jdbc:pgsql://localhost:5432/db
 */
open class PostgreSQLNGDialect : PostgreSQLDialect() {
    companion object {
        /** PostgreSQL-NG dialect name */
        const val dialectName: String = "pgsql"
    }
}
