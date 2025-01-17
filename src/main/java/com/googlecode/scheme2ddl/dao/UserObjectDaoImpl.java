package com.googlecode.scheme2ddl.dao;

import com.googlecode.scheme2ddl.domain.UserObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.support.JdbcDaoSupport;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.googlecode.scheme2ddl.TypeNamesUtil.map2TypeForDBMS;

/**
 * @author A_Reshetnikov
 * @since Date: 17.10.2012
 */
public class UserObjectDaoImpl extends JdbcDaoSupport implements UserObjectDao {

	private static final Log log = LogFactory.getLog(UserObjectDaoImpl.class);
	private Map<String, Boolean> transformParams;
	@Value("#{jobParameters['schemaName']}")
	private String schemaName;
	@Value("#{jobParameters['launchedByDBA']}")
	private boolean isLaunchedByDBA = false;
	@Value("#{jobParameters['objectFilter']}")
	private String objectFilter;
	@Value("#{jobParameters['typeFilter']}")
	private String typeFilter;
	@Value("#{jobParameters['typeFilterMode']}")
	private String typeFilterMode = "include";
	@Value("#{jobParameters['ddlTimeAfter']}")
	private String ddlTimeAfter = null;
	@Value("#{jobParameters['ddlTimeBefore']}")
	private String ddlTimeBefore = null;
	@Value("#{jobParameters['ddlTimeIn']}")
	private String ddlTimeIn = null;

	public List<UserObject> findListForProccessing() {
		String sql;
		if (isLaunchedByDBA) {
			sql = "select t.object_name, object_type " + "  from dba_objects t " + " where t.generated = 'N' "
					+ "	and lower(t.object_name) like '" + objectFilter + "' " + "   and t.owner = '" + schemaName
					+ "' " + "   and not exists (select 1 " + "          from user_nested_tables unt"
					+ "         where t.object_name = unt.table_name)";
			if (!typeFilter.isEmpty()) { // type filter is filled
				sql += " and upper(t.object_type) ";

				if (typeFilterMode.equals("exclude")) // exclude types
					sql += " NOT ";

				sql += " IN (" + typeFilter + ") ";
			}
			if (ddlTimeAfter != null)
				sql += " and t.last_ddl_time >= TO_DATE('" + ddlTimeAfter + "', 'YYYY-MM-DD HH24:MI:SS') ";
			if (ddlTimeBefore != null)
				sql += " and t.last_ddl_time <= TO_DATE('" + ddlTimeBefore + "', 'YYYY-MM-DD HH24:MI:SS') ";
			if (ddlTimeIn != null)
				sql += " and t.last_ddl_time >= SYSDATE-" + ddlTimeIn + " ";
			if (isTypeAllowed("'REFRESH GROUP'")) {
				sql += " UNION ALL " + " select rname as object_name, 'REFRESH_GROUP' as object_type "
						+ " from dba_refresh a " + " where a.rowner = '" + schemaName + "' "
						+ " and lower(a.rname) like '" + objectFilter + "' ";
			}
		} else {
			sql = "select t.object_name, object_type " + "  from user_objects t " + " where t.generated = 'N' "
					+ "	and lower(t.object_name) like '" + objectFilter + "' " + "   and not exists (select 1 "
					+ "          from user_nested_tables unt" + "         where t.object_name = unt.table_name)";
			if (!typeFilter.isEmpty()) {
				sql += " and upper(t.object_type) ";

				if (typeFilterMode.equals("exclude")) // exclude types
					sql += " NOT ";

				sql += " IN (" + typeFilter + ") ";
			}
			if (ddlTimeAfter != null)
				sql += " and t.last_ddl_time >= TO_DATE('" + ddlTimeAfter + "', 'YYYY-MM-DD HH24:MI:SS') ";
			if (ddlTimeBefore != null)
				sql += " and t.last_ddl_time <= TO_DATE('" + ddlTimeBefore + "', 'YYYY-MM-DD HH24:MI:SS') ";
			if (ddlTimeIn != null)
				sql += " and t.last_ddl_time >= SYSDATE-" + ddlTimeIn + " ";
			if (isTypeAllowed("'REFRESH GROUP'")) {
				sql += " UNION ALL " + " select rname as object_name, 'REFRESH_GROUP' as object_type "
						+ " from user_refresh " + " where lower(rname) like '" + objectFilter + "' ";
			}
		}
		return getJdbcTemplate().query(sql, new UserObjectRowMapper());
	}

	public List<UserObject> findPublicDbLinks() {
		List<UserObject> list = new ArrayList<UserObject>();
		try {
			list = getJdbcTemplate().query(
					"select db_link as object_name, 'PUBLIC DATABASE LINK' as object_type " + "from DBA_DB_LINKS "
							+ "where owner='PUBLIC'" + "	and lower(db_link) like '" + objectFilter + "' ",
					new UserObjectRowMapper());
		} catch (BadSqlGrammarException sqlGrammarException) {
			if (sqlGrammarException.getSQLException().getErrorCode() == 942) {
				String userName = null;
				try {
					userName = getDataSource().getConnection().getMetaData().getUserName();
				} catch (SQLException e) {
				}
				log.warn("WARNING: processing of 'PUBLIC DATABASE LINK' will be skipped because " + userName
						+ " no access to view it" + "\n Possible decisions:\n\n"
						+ " 1) Exclude processPublicDbLinks option in advanced config to disable this warning\n    "
						+ " <bean id=\"reader\" ...>\n"
						+ "        <property name=\"processPublicDbLinks\" value=\"false\"/>\n" + "        ...\n"
						+ "    </bean>\n" + "\n" + " 2) Or try give access to user " + userName + " with sql command\n "
						+ " GRANT SELECT_CATALOG_ROLE TO " + userName + "; \n\n");
			}
			return list;
		}

		for (UserObject userObject : list) {
			userObject.setSchema("PUBLIC");
		}
		return list;
	}

	public List<UserObject> findDmbsJobs() {
		String tableName = isLaunchedByDBA ? "dba_jobs" : "user_jobs";
		String whereClause = isLaunchedByDBA ? "schema_user = '" + schemaName + "'" : "schema_user != 'SYSMAN'";
		String sql = "select job || '' as object_name, 'DBMS JOB' as object_type " + "from  " + tableName + " where "
				+ whereClause + "	and to_char(job) like '" + objectFilter + "' ";
		// a little bit ugly, but this prevents an output from jobs if dbms job is not
		// in typeFilter
		if (!isTypeAllowed("'DBMS JOB'")) {
			sql += " and 1 = 2 ";
		}
		return getJdbcTemplate().query(sql, new UserObjectRowMapper());
	}

	public List<UserObject> findConstaints() {
		String sql;
		String prevent_constraint = new String("");
		String prevent_refconstraint = new String("");

		if (!isTypeAllowed("'CONSTRAINT'")) {
			prevent_constraint = " and 1 = 2 ";
		}
		if (!isTypeAllowed("'REF_CONSTRAINT'")) {
			prevent_refconstraint = " and 1 = 2 ";
		}
		if (isLaunchedByDBA)
			sql = " select constraint_name as object_name, 'CONSTRAINT' as object_type" + " from all_constraints "
					+ " where constraint_type != 'R' and owner = '" + schemaName + "'"
					+ " and lower(constraint_name) like '" + objectFilter + "' " + prevent_constraint + " UNION ALL "
					+ " select constraint_name as object_name, 'REF_CONSTRAINT' as object_type"
					+ " from all_constraints " + " where constraint_type = 'R' and owner = '" + schemaName + "'"
					+ " and lower(constraint_name) like '" + objectFilter + "' " + prevent_refconstraint;
		else
			sql = " select constraint_name as object_name, 'CONSTRAINT' as object_type"
					+ " from user_constraints where  constraint_type != 'R'" + " and lower(constraint_name) like '"
					+ objectFilter + "' " + prevent_constraint + " UNION ALL "
					+ " select constraint_name as object_name, 'REF_CONSTRAINT' as object_type"
					+ " from user_constraints where constraint_type = 'R'" + " and lower(constraint_name) like '"
					+ objectFilter + "' " + prevent_refconstraint;

		return getJdbcTemplate().query(sql, new UserObjectRowMapper());
	}

	public String findPrimaryDDL(final String type, final String name) {
		if (isLaunchedByDBA)
			return executeDbmsMetadataGetDdl("select dbms_metadata.get_ddl(?, ?, ?) from dual", type, name, schemaName);
		else
			return executeDbmsMetadataGetDdl("select dbms_metadata.get_ddl(?, ?) from dual", type, name, null);
	}

	private String executeDbmsMetadataGetDdl(final String query, final String type, final String name,
			final String schema) {
		return (String) getJdbcTemplate().execute(new ConnectionCallback() {
			public String doInConnection(Connection connection) throws SQLException, DataAccessException {
				applyTransformParameters(connection);
				PreparedStatement ps = connection.prepareStatement(query);
				ps.setString(1, type);
				ps.setString(2, name);
				if (schema != null) {
					ps.setString(3, schema);
				}
				ResultSet rs = null;
				try {
					rs = ps.executeQuery();
				} catch (SQLException e) {
					// log.trace(String.format("Error during select dbms_metadata.get_ddl('%s',
					// '%s') from dual\n" +
					// "Try to exclude type '%s' in advanced config excludes section\n", type, name,
					// map2TypeForConfig(type)));
					// log.trace(String.format("Sample:\n\n" +
					// " <util:map id=\"excludes\">\n" +
					// "...\n" +
					// " <entry key=\"%s\">\n" +
					// " <set>\n" +
					// " <value>%s</value>\n" +
					// " </set>\n" +
					// " </entry>\n" +
					// "...\n" +
					// "</util:map>", map2TypeForConfig(type), name));
					throw e;
				}
				try {
					if (rs.next()) {
						return rs.getString(1);
					}
				} finally {
					rs.close();
				}
				return null;
			}
		});
	}

	public String findDependentDLLByTypeName(final String type, final String name) {

		return (String) getJdbcTemplate().execute(new ConnectionCallback() {
			final String query = "select dbms_metadata.get_dependent_ddl(?, ?, ?) from dual";

			public Object doInConnection(Connection connection) throws SQLException, DataAccessException {
				applyTransformParameters(connection);
				PreparedStatement ps = connection.prepareStatement(query);
				ps.setString(1, type);
				ps.setString(2, name);
				ps.setString(3, isLaunchedByDBA ? schemaName : null);
				ResultSet rs;
				try {
					rs = ps.executeQuery();
				} catch (SQLException e) {
					log.trace(String.format("Error during select dbms_metadata.get_dependent_ddl(%s, %s) from dual",
							type, name));
					return "";
				}
				try {
					if (rs.next()) {
						return rs.getString(1);
					}
				} finally {
					rs.close();
				}
				return null;
			}
		});
	}

	public String findDDLInPublicScheme(String type, String name) {
		return executeDbmsMetadataGetDdl("select dbms_metadata.get_ddl(?, ?, ?) from dual", type, name, "PUBLIC");
	}

	public String findDbmsJobDDL(String name) {
		String sql;
		if (isLaunchedByDBA)
			// The 'dbms_job.user_export' function does not work with sys/dba users (can't
			// find users jobs). :(
			sql = "DECLARE\n" + " callstr VARCHAR2(4096);\n" + "BEGIN\n" + "  sys.dbms_ijob.full_export(" + name
					+ ", callstr);\n" + ":done := callstr; END;";
		else
			sql = "DECLARE\n" + " callstr VARCHAR2(4096);\n" + "BEGIN\n" + "  dbms_job.user_export(" + name
					+ ", callstr);\n" + ":done := callstr; " + "END;";

		return (String) getJdbcTemplate().execute(sql, new CallableStatementCallbackImpl());
	}

	public String findRefGroupDDL(String type, final String name) {
		if (isLaunchedByDBA)
			return findPrimaryDDL(map2TypeForDBMS(type), name);
		else
			return (String) getJdbcTemplate().execute(new ConnectionCallback() {
				final String query = "SELECT 'begin'" + "|| CHR (13) || CHR (10)"
						+ "|| '-- dbms_refresh.destroy(name      => '''" + "|| rname" + "|| ''');'"
						+ "|| CHR (13) || CHR (10)" + "|| '   dbms_refresh.make   (name      => '''" + "|| rname"
						+ "|| ''', '" + "|| CHR (13) || CHR (10)" + "|| '                        list      => '''"
						+ "|| listagg(name, ',') within group (order by name)" + "|| ''','" + "|| CHR (13) || CHR (10)"
						+ "|| '                        next_date => '"
						+ "|| CASE WHEN MAX(next_date) IS NULL THEN 'NULL' ELSE 'to_date(''' || TO_CHAR (MAX (next_date), 'DD.MM.YYYY HH24:MI:SS') || ''', ''DD.MM.YYYY HH24:MI:SS'')' END"
						+ "|| ', '" + "|| CHR (13) || CHR (10)" + "|| '                        interval  => '"
						+ "|| CASE WHEN MAX(interval) IS NULL THEN 'NULL' ELSE '''' || MAX (REPLACE(interval, '''', '''''')) || '''' END "
						+ "|| ');'" + "|| CHR (13) || CHR (10)" + "|| '   commit;'" + "|| CHR (13) || CHR (10)"
						+ "|| 'end;'" + "|| CHR (13) || CHR (10)" + "|| '/'" + "|| CHR (13) || CHR (10)"
						+ " FROM user_refresh_children " + " WHERE rname = UPPER ('" + name + "')" + " GROUP BY rname";

				public Object doInConnection(Connection connection) throws SQLException, DataAccessException {
					// todo sl4j logger.debug( "query: \n {} ", query);
					applyTransformParameters(connection);
					PreparedStatement ps = connection.prepareStatement(query);

					ResultSet rs;

					try {
						rs = ps.executeQuery();
					} catch (SQLException e) {
						log.trace(String.format("Error during select ddl for refresh group (%s)", name));
						return "";
					}
					try {
						if (rs.next()) {
							return rs.getString(1);
						}
					} finally {
						rs.close();
					}
					return null;
				}
			});
	}

	public void applyTransformParameters(Connection connection) throws SQLException {
		for (String parameterName : transformParams.keySet()) {
			connection.setAutoCommit(false);
			// setBoolean doesn't convert java boolean to pl/sql boolean, so used such query
			// building
			String sql = String.format(
					"BEGIN " + " dbms_metadata.set_transform_param(DBMS_METADATA.SESSION_TRANSFORM,'%s',%s);" + " END;",
					parameterName, transformParams.get(parameterName));
			PreparedStatement ps = connection.prepareCall(sql);
			// ps.setString(1, parameterName);
			// ps.setBoolean(2, transformParams.get(parameterName) ); //In general this
			// doesn't work
			ps.execute();
		}
	}

	public void setTransformParams(Map<String, Boolean> transformParams) {
		this.transformParams = transformParams;
	}

	public void setSchemaName(String schemaName) {
		this.schemaName = schemaName;
	}

	public void setLaunchedByDBA(boolean launchedByDBA) {
		this.isLaunchedByDBA = launchedByDBA;
	}

	private class CallableStatementCallbackImpl implements CallableStatementCallback {
		public Object doInCallableStatement(CallableStatement callableStatement)
				throws SQLException, DataAccessException {
			callableStatement.registerOutParameter(1, java.sql.Types.VARCHAR);
			callableStatement.executeUpdate();
			return callableStatement.getString(1);
		}
	}

	private class UserObjectRowMapper implements RowMapper {
		public UserObject mapRow(ResultSet rs, int rowNum) throws SQLException {
			UserObject userObject = new UserObject();
			userObject.setName(rs.getString("object_name"));
			userObject.setType(rs.getString("object_type"));
			userObject.setSchema(schemaName == null ? "" : schemaName);
			return userObject;
		}
	}

	private boolean isTypeAllowed(String typeName) {
		if (typeFilter.isEmpty()) // empty type filter means all types are allowed
			return true;
		if (typeFilterMode.equals("include") && typeFilter.contains(typeName)) // given typeName is in the typeFilter
			return true;
		if (typeFilterMode.equals("exclude") && !typeFilter.contains(typeName)) // given typeName is not in the
																				// typeFilter
			return true;

		return false;
	}
}
