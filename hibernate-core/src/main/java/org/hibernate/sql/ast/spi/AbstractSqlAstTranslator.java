/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.ast.spi;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.hibernate.LockMode;
import org.hibernate.QueryException;
import org.hibernate.dialect.RowLockStrategy;
import org.hibernate.metamodel.mapping.EntityAssociationMapping;
import org.hibernate.metamodel.mapping.ModelPart;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.Queryable;
import org.hibernate.query.IllegalQueryOperationException;
import org.hibernate.query.sqm.sql.internal.SqmPathInterpretation;
import org.hibernate.sql.ast.tree.cte.CteMaterialization;
import org.hibernate.sql.ast.tree.cte.CteSearchClauseKind;
import org.hibernate.query.FetchClauseType;
import org.hibernate.LockOptions;
import org.hibernate.NotYetImplementedFor6Exception;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.AbstractDelegatingWrapperOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.FilterJdbcParameter;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.internal.util.collections.Stack;
import org.hibernate.internal.util.collections.StandardStack;
import org.hibernate.metamodel.mapping.CollectionPart;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.ModelPartContainer;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.mapping.SqlExpressable;
import org.hibernate.metamodel.mapping.internal.BasicValuedCollectionPart;
import org.hibernate.metamodel.mapping.internal.EntityCollectionPart;
import org.hibernate.metamodel.mapping.internal.SimpleForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.Loadable;
import org.hibernate.query.ComparisonOperator;
import org.hibernate.query.Limit;
import org.hibernate.query.NullPrecedence;
import org.hibernate.query.SortOrder;
import org.hibernate.query.UnaryArithmeticOperator;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.function.AbstractSqmSelfRenderingFunctionDescriptor;
import org.hibernate.query.sqm.function.SqmFunctionDescriptor;
import org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation;
import org.hibernate.query.sqm.tree.expression.Conversion;
import org.hibernate.sql.ast.Clause;
import org.hibernate.sql.ast.SqlAstJoinType;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlTreeCreationException;
import org.hibernate.sql.ast.tree.MutationStatement;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.ast.tree.cte.CteColumn;
import org.hibernate.sql.ast.tree.cte.CteContainer;
import org.hibernate.sql.ast.tree.cte.CteStatement;
import org.hibernate.sql.ast.tree.cte.SearchClauseSpecification;
import org.hibernate.sql.ast.tree.delete.DeleteStatement;
import org.hibernate.sql.ast.tree.expression.Any;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.CaseSearchedExpression;
import org.hibernate.sql.ast.tree.expression.CaseSimpleExpression;
import org.hibernate.sql.ast.tree.expression.CastTarget;
import org.hibernate.sql.ast.tree.expression.Collate;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Distinct;
import org.hibernate.sql.ast.tree.expression.Duration;
import org.hibernate.sql.ast.tree.expression.DurationUnit;
import org.hibernate.sql.ast.tree.expression.EntityTypeLiteral;
import org.hibernate.sql.ast.tree.expression.Every;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.ExtractUnit;
import org.hibernate.sql.ast.tree.expression.Format;
import org.hibernate.sql.ast.tree.expression.JdbcLiteral;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.Literal;
import org.hibernate.sql.ast.tree.expression.LiteralAsParameter;
import org.hibernate.sql.ast.tree.expression.NullnessLiteral;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.expression.SelfRenderingExpression;
import org.hibernate.sql.ast.tree.expression.SqlSelectionExpression;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.sql.ast.tree.expression.SqlTupleContainer;
import org.hibernate.sql.ast.tree.expression.Star;
import org.hibernate.sql.ast.tree.expression.Summarization;
import org.hibernate.sql.ast.tree.expression.TrimSpecification;
import org.hibernate.sql.ast.tree.expression.UnaryOperation;
import org.hibernate.sql.ast.tree.from.FromClause;
import org.hibernate.sql.ast.tree.from.LazyTableGroup;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.from.TableReference;
import org.hibernate.sql.ast.tree.from.TableReferenceJoin;
import org.hibernate.sql.ast.tree.from.VirtualTableGroup;
import org.hibernate.sql.ast.tree.insert.InsertStatement;
import org.hibernate.sql.ast.tree.insert.Values;
import org.hibernate.sql.ast.tree.predicate.BetweenPredicate;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.ExistsPredicate;
import org.hibernate.sql.ast.tree.predicate.FilterPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.InListPredicate;
import org.hibernate.sql.ast.tree.predicate.InSubQueryPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.LikePredicate;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.NullnessPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.predicate.SelfRenderingPredicate;
import org.hibernate.sql.ast.tree.select.QueryGroup;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectClause;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.ast.tree.select.SortSpecification;
import org.hibernate.sql.ast.tree.update.Assignment;
import org.hibernate.sql.ast.tree.update.UpdateStatement;
import org.hibernate.sql.exec.ExecutionException;
import org.hibernate.sql.exec.internal.JdbcParameterBindingImpl;
import org.hibernate.sql.exec.internal.JdbcParameterImpl;
import org.hibernate.sql.exec.internal.JdbcParametersImpl;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcDelete;
import org.hibernate.sql.exec.spi.JdbcInsert;
import org.hibernate.sql.exec.spi.JdbcLockStrategy;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;
import org.hibernate.sql.exec.spi.JdbcParameterBinding;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.exec.spi.JdbcSelect;
import org.hibernate.sql.exec.spi.JdbcUpdate;
import org.hibernate.sql.results.internal.SqlSelectionImpl;
import org.hibernate.sql.results.jdbc.internal.JdbcValuesMappingProducerStandard;
import org.hibernate.type.BasicType;
import org.hibernate.type.IntegerType;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.StringType;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.jdbc.JdbcLiteralFormatter;
import org.hibernate.type.descriptor.jdbc.JdbcTypeDescriptor;

import static org.hibernate.query.TemporalUnit.NANOSECOND;
import static org.hibernate.sql.ast.SqlTreePrinter.logSqlAst;
import static org.hibernate.sql.results.graph.DomainResultGraphPrinter.logDomainResultGraph;

/**
 * @author Steve Ebersole
 */
public abstract class AbstractSqlAstTranslator<T extends JdbcOperation> implements SqlAstTranslator<T>, SqlAppender {

	private static final QueryLiteral<Integer> ONE_LITERAL = new QueryLiteral<>( 1, IntegerType.INSTANCE );

	// pre-req state
	private final SessionFactoryImplementor sessionFactory;

	// In-flight state
	private final StringBuilder sqlBuffer = new StringBuilder();

	private final List<JdbcParameterBinder> parameterBinders = new ArrayList<>();
	private final JdbcParametersImpl jdbcParameters = new JdbcParametersImpl();

	private final Set<FilterJdbcParameter> filterJdbcParameters = new HashSet<>();

	private final Stack<Clause> clauseStack = new StandardStack<>();
	private final Stack<QueryPart> queryPartStack = new StandardStack<>();

	private final Dialect dialect;
	private final Statement statement;
	private final Set<String> affectedTableNames = new HashSet<>();
	private String dmlTargetTableAlias;
	private boolean needsSelectAliases;
	// We must reset the queryPartForRowNumbering fields to null if a query part is visited that does not
	// contribute to the row numbering i.e. if the query part is a sub-query in the where clause.
	// To determine whether a query part contributes to row numbering, we remember the clause depth
	// and when visiting a query part, compare the current clause depth against the remembered one.
	private QueryPart queryPartForRowNumbering;
	private int queryPartForRowNumberingClauseDepth = -1;
	private int queryPartForRowNumberingAliasCounter;
	private int queryGroupAliasCounter;
	private transient AbstractSqmSelfRenderingFunctionDescriptor castFunction;
	private transient LazySessionWrapperOptions lazySessionWrapperOptions;

	private boolean inlineParameters;

	private Map<JdbcParameter, JdbcParameterBinding> appliedParameterBindings = Collections.emptyMap();
	private JdbcParameterBindings jdbcParameterBindings;
	private LockOptions lockOptions;
	private Limit limit;
	private JdbcParameter offsetParameter;
	private JdbcParameter limitParameter;
	private ForUpdateClause forUpdate;

	public Dialect getDialect() {
		return dialect;
	}

	@SuppressWarnings("WeakerAccess")
	protected AbstractSqlAstTranslator(SessionFactoryImplementor sessionFactory, Statement statement) {
		this.sessionFactory = sessionFactory;
		this.statement = statement;
		this.dialect = sessionFactory.getJdbcServices().getDialect();
	}

	public SessionFactoryImplementor getSessionFactory() {
		return sessionFactory;
	}

	protected AbstractSqmSelfRenderingFunctionDescriptor castFunction() {
		if ( castFunction == null ) {
			castFunction = (AbstractSqmSelfRenderingFunctionDescriptor) sessionFactory
					.getQueryEngine()
					.getSqmFunctionRegistry()
					.findFunctionDescriptor( "cast" );
		}
		return castFunction;
	}

	protected WrapperOptions getWrapperOptions() {
		if ( lazySessionWrapperOptions == null ) {
			lazySessionWrapperOptions = new LazySessionWrapperOptions( sessionFactory );
		}
		return lazySessionWrapperOptions;
	}

	/**
	 * A lazy session implementation that is needed for rendering literals.
	 * Usually, only the {@link org.hibernate.type.descriptor.WrapperOptions} interface is needed,
	 * but for creating LOBs, it might be to have a full blown session.
	 */
	private static class LazySessionWrapperOptions extends AbstractDelegatingWrapperOptions {

		private final SessionFactoryImplementor sessionFactory;
		private SessionImplementor session;

		public LazySessionWrapperOptions(SessionFactoryImplementor sessionFactory) {
			this.sessionFactory = sessionFactory;
		}

		public void cleanup() {
			if ( session != null ) {
				session.close();
				session = null;
			}
		}

		@Override
		protected SessionImplementor delegate() {
			if ( session == null ) {
				session = (SessionImplementor) sessionFactory.openTemporarySession();
			}
			return session;
		}

		@Override
		public SharedSessionContractImplementor getSession() {
			return delegate();
		}

		@Override
		public boolean useStreamForLobBinding() {
			return sessionFactory.getFastSessionServices().useStreamForLobBinding();
		}

		@Override
		public int getPreferredSqlTypeCodeForBoolean() {
			return sessionFactory.getFastSessionServices().getPreferredSqlTypeCodeForBoolean();
		}

		@Override
		public JdbcTypeDescriptor remapSqlTypeDescriptor(JdbcTypeDescriptor jdbcTypeDescriptor) {
			return sessionFactory.getFastSessionServices().remapSqlTypeDescriptor( jdbcTypeDescriptor );
		}

		@Override
		public TimeZone getJdbcTimeZone() {
			return sessionFactory.getSessionFactoryOptions().getJdbcTimeZone();
		}
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// for tests, for now
	public String getSql() {
		return sqlBuffer.toString();
	}

	protected void cleanup() {
		if ( lazySessionWrapperOptions != null ) {
			lazySessionWrapperOptions.cleanup();
			lazySessionWrapperOptions = null;
		}
		this.jdbcParameterBindings = null;
		this.lockOptions = null;
		this.limit = null;
		setOffsetParameter( null );
		setLimitParameter( null );
	}

	@SuppressWarnings("WeakerAccess")
	public List<JdbcParameterBinder> getParameterBinders() {
		return parameterBinders;
	}
	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	public Set<FilterJdbcParameter> getFilterJdbcParameters() {
		return filterJdbcParameters;
	}

	@SuppressWarnings("unused")
	protected SqlAppender getSqlAppender() {
		return this;
	}

	@Override
	public Set<String> getAffectedTableNames() {
		return affectedTableNames;
	}

	protected String getDmlTargetTableAlias() {
		return dmlTargetTableAlias;
	}

	protected Statement getStatement() {
		return statement;
	}

	@Override
	public boolean supportsFilterClause() {
		// By default we report false because not many dialects support this
		return false;
	}

	@Override
	public void appendSql(String fragment) {
		sqlBuffer.append( fragment );
	}

	@Override
	public void appendSql(char fragment) {
		sqlBuffer.append( fragment );
	}

	protected JdbcServices getJdbcServices() {
		return getSessionFactory().getJdbcServices();
	}

	protected void addAppliedParameterBinding(JdbcParameter parameter, JdbcParameterBinding binding) {
		if ( appliedParameterBindings.isEmpty() ) {
			appliedParameterBindings = new IdentityHashMap<>();
		}
		if ( binding == null ) {
			appliedParameterBindings.put( parameter, null );
		}
		else {
			final JdbcMapping bindType = binding.getBindType();
			final Object value = bindType.getJavaTypeDescriptor()
					.getMutabilityPlan()
					.deepCopy( binding.getBindValue() );
			appliedParameterBindings.put( parameter, new JdbcParameterBindingImpl( bindType, value ) );
		}
	}

	protected Map<JdbcParameter, JdbcParameterBinding> getAppliedParameterBindings() {
		return appliedParameterBindings;
	}

	protected JdbcLockStrategy getJdbcLockStrategy() {
		return lockOptions == null ? JdbcLockStrategy.FOLLOW_ON : JdbcLockStrategy.NONE;
	}

	protected JdbcParameterBindings getJdbcParameterBindings() {
		return jdbcParameterBindings;
	}

	protected LockOptions getLockOptions() {
		return lockOptions;
	}

	protected Limit getLimit() {
		return limit;
	}

	protected boolean hasLimit() {
		return limit != null && !limit.isEmpty();
	}

	protected boolean hasOffset(QueryPart queryPart) {
		if ( queryPart.isRoot() && hasLimit() && limit.getFirstRowJpa() != 0 ) {
			return true;
		}
		else {
			return queryPart.getOffsetClauseExpression() != null;
		}
	}

	protected boolean useOffsetFetchClause(QueryPart queryPart) {
		return !queryPart.isRoot() || limit == null || limit.isEmpty();
	}

	protected boolean isRowsOnlyFetchClauseType(QueryPart queryPart) {
		if ( queryPart.isRoot() && hasLimit() || queryPart.getFetchClauseType() == null ) {
			return true;
		}
		else {
			return queryPart.getFetchClauseType() == FetchClauseType.ROWS_ONLY;
		}
	}

	protected JdbcParameter getOffsetParameter() {
		return offsetParameter;
	}

	protected void setOffsetParameter(JdbcParameter offsetParameter) {
		this.offsetParameter = offsetParameter;
	}

	protected JdbcParameter getLimitParameter() {
		return limitParameter;
	}

	protected void setLimitParameter(JdbcParameter limitParameter) {
		this.limitParameter = limitParameter;
	}

	protected <R> R interpretExpression(Expression expression, JdbcParameterBindings jdbcParameterBindings) {
		if ( expression instanceof Literal ) {
			return (R) ( (Literal) expression ).getLiteralValue();
		}
		else if ( expression instanceof JdbcParameter ) {
			if ( jdbcParameterBindings == null ) {
				throw new IllegalArgumentException( "Can't interpret expression because no parameter bindings are available!" );
			}
			return (R) getParameterBindValue( (JdbcParameter) expression );
		}
		else if ( expression instanceof SqmParameterInterpretation ) {
			if ( jdbcParameterBindings == null ) {
				throw new IllegalArgumentException( "Can't interpret expression because no parameter bindings are available!" );
			}
			return (R) getParameterBindValue( (JdbcParameter) ( (SqmParameterInterpretation) expression).getResolvedExpression() );
		}
		throw new UnsupportedOperationException( "Can't interpret expression: " + expression );
	}

	protected void renderExpressionAsLiteral(Expression expression, JdbcParameterBindings jdbcParameterBindings) {
		if ( expression instanceof Literal ) {
			expression.accept( this );
			return;
		}
		else if ( expression instanceof JdbcParameter ) {
			if ( jdbcParameterBindings == null ) {
				throw new IllegalArgumentException( "Can't interpret expression because no parameter bindings are available!" );
			}
			final JdbcParameter parameter = (JdbcParameter) expression;
			renderAsLiteral( parameter, getParameterBindValue( parameter ) );
			return;
		}
		else if ( expression instanceof SqmParameterInterpretation ) {
			if ( jdbcParameterBindings == null ) {
				throw new IllegalArgumentException( "Can't interpret expression because no parameter bindings are available!" );
			}
			final JdbcParameter parameter = (JdbcParameter) ( (SqmParameterInterpretation) expression).getResolvedExpression();
			renderAsLiteral( parameter, getParameterBindValue( parameter ) );
			return;
		}
		throw new UnsupportedOperationException( "Can't render expression as literal: " + expression );
	}

	protected Object getParameterBindValue(JdbcParameter parameter) {
		final JdbcParameterBinding binding;
		if ( parameter == getOffsetParameter() ) {
			binding = new JdbcParameterBindingImpl( IntegerType.INSTANCE, getLimit().getFirstRow() );
		}
		else if ( parameter == getLimitParameter() ) {
			binding = new JdbcParameterBindingImpl( IntegerType.INSTANCE, getLimit().getMaxRows() );
		}
		else {
			binding = jdbcParameterBindings.getBinding( parameter );
		}
		addAppliedParameterBinding( parameter, binding );
		return binding.getBindValue();
	}

	protected boolean isCurrentlyInPredicate() {
		return clauseStack.getCurrent() == Clause.WHERE
				|| clauseStack.getCurrent() == Clause.HAVING;
	}

	protected boolean inOverClause() {
		return clauseStack.findCurrentFirst(
				clause -> {
					if ( clause == Clause.OVER ) {
						return true;
					}
					return null;
				}
		) != null;
	}

	protected Stack<Clause> getClauseStack() {
		return clauseStack;
	}

	protected Stack<QueryPart> getQueryPartStack() {
		return queryPartStack;
	}

	@Override
	public T translate(JdbcParameterBindings jdbcParameterBindings, QueryOptions queryOptions) {
		try {
			this.jdbcParameterBindings = jdbcParameterBindings;
			this.lockOptions = queryOptions.getLockOptions().makeCopy();
			this.limit = queryOptions.getLimit() == null ? null : queryOptions.getLimit().makeCopy();
			final JdbcOperation jdbcOperation;
			if ( statement instanceof DeleteStatement ) {
				jdbcOperation = translateDelete( (DeleteStatement) statement );
			}
			else if ( statement instanceof UpdateStatement ) {
				jdbcOperation = translateUpdate( (UpdateStatement) statement );
			}
			else if ( statement instanceof InsertStatement ) {
				jdbcOperation = translateInsert( (InsertStatement) statement );
			}
			else if ( statement instanceof SelectStatement ) {
				jdbcOperation = translateSelect( (SelectStatement) statement );
			}
			else {
				throw new IllegalArgumentException( "Unexpected statement!" );
			}

			if ( jdbcParameterBindings != null && CollectionHelper.isNotEmpty( getFilterJdbcParameters() ) ) {
				for ( FilterJdbcParameter filterJdbcParameter : getFilterJdbcParameters() ) {
					jdbcParameterBindings.addBinding(
							filterJdbcParameter.getParameter(),
							filterJdbcParameter.getBinding()
					);
				}
			}

			return (T) jdbcOperation;
		}
		finally {
			cleanup();
		}
	}

	protected JdbcDelete translateDelete(DeleteStatement sqlAst) {
		visitDeleteStatement( sqlAst );

		return new JdbcDelete(
				getSql(),
				getParameterBinders(),
				getAffectedTableNames(),
				getFilterJdbcParameters(),
				getAppliedParameterBindings()
		);
	}

	protected JdbcUpdate translateUpdate(UpdateStatement sqlAst) {
		visitUpdateStatement( sqlAst );

		return new JdbcUpdate(
				getSql(),
				getParameterBinders(),
				getAffectedTableNames(),
				getFilterJdbcParameters(),
				getAppliedParameterBindings()
		);
	}

	protected JdbcInsert translateInsert(InsertStatement sqlAst) {
		visitInsertStatement( sqlAst );

		return new JdbcInsert(
				getSql(),
				getParameterBinders(),
				getAffectedTableNames(),
				getFilterJdbcParameters(),
				getAppliedParameterBindings()
		);
	}

	protected JdbcSelect translateSelect(SelectStatement sqlAstSelect) {
		logDomainResultGraph( sqlAstSelect.getDomainResultDescriptors() );
		logSqlAst( sqlAstSelect );

		visitSelectStatement( sqlAstSelect );

		final int rowsToSkip;
		return new JdbcSelect(
				getSql(),
				getParameterBinders(),
				new JdbcValuesMappingProducerStandard(
						sqlAstSelect.getQuerySpec().getSelectClause().getSqlSelections(),
						sqlAstSelect.getDomainResultDescriptors()
				),
				getAffectedTableNames(),
				getFilterJdbcParameters(),
				rowsToSkip = getRowsToSkip( sqlAstSelect, getJdbcParameterBindings() ),
				getMaxRows( sqlAstSelect, getJdbcParameterBindings(), rowsToSkip ),
				getAppliedParameterBindings(),
				getJdbcLockStrategy(),
				getOffsetParameter(),
				getLimitParameter()
		);
	}

	protected int getRowsToSkip(SelectStatement sqlAstSelect, JdbcParameterBindings jdbcParameterBindings) {
		if ( hasLimit() ) {
			if ( offsetParameter != null && needsRowsToSkip() ) {
				return interpretExpression( offsetParameter, jdbcParameterBindings );
			}
		}
		else {
			final Expression offsetClauseExpression = sqlAstSelect.getQueryPart().getOffsetClauseExpression();
			if ( offsetClauseExpression != null && needsRowsToSkip() ) {
				return interpretExpression( offsetClauseExpression, jdbcParameterBindings );
			}
		}
		return 0;
	}

	protected int getMaxRows(SelectStatement sqlAstSelect, JdbcParameterBindings jdbcParameterBindings, int rowsToSkip) {
		if ( hasLimit() ) {
			if ( limitParameter != null && needsMaxRows() ) {
				final Number fetchCount = interpretExpression( limitParameter, jdbcParameterBindings );
				return rowsToSkip + fetchCount.intValue();
			}
		}
		else {
			final Expression fetchClauseExpression = sqlAstSelect.getQueryPart().getFetchClauseExpression();
			if ( fetchClauseExpression != null && needsMaxRows() ) {
				final Number fetchCount = interpretExpression( fetchClauseExpression, jdbcParameterBindings );
				return rowsToSkip + fetchCount.intValue();
			}
		}
		return Integer.MAX_VALUE;
	}

	protected boolean needsRowsToSkip() {
		return false;
	}

	protected boolean needsMaxRows() {
		return false;
	}

	protected void prepareLimitOffsetParameters() {
		final Limit limit = getLimit();
		if ( limit.getFirstRow() != null ) {
			setOffsetParameter(
					new OffsetJdbcParameter(
							sessionFactory.getTypeConfiguration().getBasicTypeForJavaType( Integer.class )
					)
			);
		}
		if ( limit.getMaxRows() != null ) {
			setLimitParameter(
					new LimitJdbcParameter(
							sessionFactory.getTypeConfiguration().getBasicTypeForJavaType( Integer.class )
					)
			);
		}
	}

	private static class OffsetJdbcParameter extends JdbcParameterImpl {

		public OffsetJdbcParameter(BasicType<Integer> type) {
			super( type );
		}

		@Override
		public void bindParameterValue(
				PreparedStatement statement,
				int startPosition,
				JdbcParameterBindings jdbcParamBindings,
				ExecutionContext executionContext) throws SQLException {
			getJdbcMapping().getJdbcValueBinder().bind(
					statement,
					executionContext.getQueryOptions().getLimit().getFirstRow(),
					startPosition,
					executionContext.getSession()
			);
		}
	}

	private static class LimitJdbcParameter extends JdbcParameterImpl {

		public LimitJdbcParameter(BasicType<Integer> type) {
			super( type );
		}

		@Override
		public void bindParameterValue(
				PreparedStatement statement,
				int startPosition,
				JdbcParameterBindings jdbcParamBindings,
				ExecutionContext executionContext) throws SQLException {
			getJdbcMapping().getJdbcValueBinder().bind(
					statement,
					executionContext.getQueryOptions().getLimit().getMaxRows(),
					startPosition,
					executionContext.getSession()
			);
		}
	}

	@Override
	public void visitSelectStatement(SelectStatement statement) {
		String oldDmlTargetTableAlias = dmlTargetTableAlias;
		dmlTargetTableAlias = null;
		try {
			visitCteContainer( statement );
			statement.getQueryPart().accept( this );
		}
		finally {
			dmlTargetTableAlias = oldDmlTargetTableAlias;
		}
	}

	@Override
	public void visitDeleteStatement(DeleteStatement statement) {
		String oldDmlTargetTableAlias = dmlTargetTableAlias;
		dmlTargetTableAlias = null;
		try {
			visitCteContainer( statement );
			dmlTargetTableAlias = statement.getTargetTable().getIdentificationVariable();
			visitDeleteStatementOnly( statement );
		}
		finally {
			dmlTargetTableAlias = oldDmlTargetTableAlias;
		}
	}

	@Override
	public void visitUpdateStatement(UpdateStatement statement) {
		String oldDmlTargetTableAlias = dmlTargetTableAlias;
		dmlTargetTableAlias = null;
		try {
			visitCteContainer( statement );
			dmlTargetTableAlias = statement.getTargetTable().getIdentificationVariable();
			visitUpdateStatementOnly( statement );
		}
		finally {
			dmlTargetTableAlias = oldDmlTargetTableAlias;
		}
	}

	@Override
	public void visitAssignment(Assignment assignment) {
		throw new SqlTreeCreationException( "Encountered unexpected assignment clause" );
	}

	@Override
	public void visitInsertStatement(InsertStatement statement) {
		String oldDmlTargetTableAlias = dmlTargetTableAlias;
		dmlTargetTableAlias = null;
		try {
			visitCteContainer( statement );
			visitInsertStatementOnly( statement );
		}
		finally {
			dmlTargetTableAlias = oldDmlTargetTableAlias;
		}
	}

	protected void visitDeleteStatementOnly(DeleteStatement statement) {
		// todo (6.0) : to support joins we need dialect support
		appendSql( "delete from " );
		final Stack<Clause> clauseStack = getClauseStack();
		try {
			clauseStack.push( Clause.DELETE );
			renderTableReference( statement.getTargetTable(), LockMode.NONE );
		}
		finally {
			clauseStack.pop();
		}

		if ( statement.getRestriction() != null ) {
			try {
				clauseStack.push( Clause.WHERE );
				appendSql( " where " );
				statement.getRestriction().accept( this );
			}
			finally {
				clauseStack.pop();
			}
		}
		visitReturningColumns( statement );
	}

	protected void visitUpdateStatementOnly(UpdateStatement statement) {
		// todo (6.0) : to support joins we need dialect support
		appendSql( "update " );
		final Stack<Clause> clauseStack = getClauseStack();
		try {
			clauseStack.push( Clause.UPDATE );
			renderTableReference( statement.getTargetTable(), LockMode.NONE );
		}
		finally {
			clauseStack.pop();
		}

		appendSql( " set " );
		boolean firstPass = true;
		try {
			clauseStack.push( Clause.SET );
			for ( Assignment assignment : statement.getAssignments() ) {
				if ( firstPass ) {
					firstPass = false;
				}
				else {
					appendSql( ", " );
				}

				final List<ColumnReference> columnReferences = assignment.getAssignable().getColumnReferences();
				if ( columnReferences.size() == 1 ) {
					columnReferences.get( 0 ).accept( this );
				}
				else {
					appendSql( " (" );
					for ( ColumnReference columnReference : columnReferences ) {
						columnReference.accept( this );
					}
					appendSql( ") " );
				}
				appendSql( " = " );
				assignment.getAssignedValue().accept( this );
			}
		}
		finally {
			clauseStack.pop();
		}

		if ( statement.getRestriction() != null ) {
			appendSql( " where " );
			try {
				clauseStack.push( Clause.WHERE );
				statement.getRestriction().accept( this );
			}
			finally {
				clauseStack.pop();
			}
		}
		visitReturningColumns( statement );
	}

	protected void visitInsertStatementOnly(InsertStatement statement) {
		appendSql( "insert into " );
		appendSql( statement.getTargetTable().getTableExpression() );

		appendSql( " (" );
		boolean firstPass = true;

		final List<ColumnReference> targetColumnReferences = statement.getTargetColumnReferences();
		if ( targetColumnReferences == null ) {
			renderImplicitTargetColumnSpec();
		}
		else {
			for (ColumnReference targetColumnReference : targetColumnReferences) {
				if (firstPass) {
					firstPass = false;
				}
				else {
					appendSql( ", " );
				}

				appendSql( targetColumnReference.getColumnExpression() );
			}
		}

		appendSql( ") " );

		if ( statement.getSourceSelectStatement() != null ) {
			statement.getSourceSelectStatement().accept( this );
		}
		else {
			visitValuesList( statement.getValuesList() );
		}
		visitReturningColumns( statement );
	}

	private void renderImplicitTargetColumnSpec() {
	}

	protected void visitValuesList(List<Values> valuesList) {
		appendSql("values");
		boolean firstTuple = true;
		final Stack<Clause> clauseStack = getClauseStack();
		try {
			clauseStack.push( Clause.VALUES );
			for ( Values values : valuesList ) {
				if ( firstTuple ) {
					firstTuple = false;
				}
				else {
					appendSql( ", " );
				}
				appendSql( " (" );
				boolean firstExpr = true;
				for ( Expression expression : values.getExpressions() ) {
					if ( firstExpr ) {
						firstExpr = false;
					}
					else {
						appendSql( ", " );
					}
					expression.accept( this );
				}
				appendSql( ")" );
			}
		}
		finally {
			clauseStack.pop();
		}
	}

	protected void visitForUpdateClause(QuerySpec querySpec) {
		if ( querySpec.isRoot() ) {
			if ( forUpdate != null ) {
				final Boolean followOnLocking = getLockOptions().getFollowOnLocking();
				if ( Boolean.TRUE.equals( followOnLocking ) ) {
					lockOptions = null;
				}
				else {
					forUpdate.merge( getLockOptions() );
					forUpdate.applyAliases( dialect.getWriteRowLockStrategy(), querySpec );
					if ( LockMode.READ.lessThan( forUpdate.getLockMode() ) ) {
						final LockStrategy lockStrategy = determineLockingStrategy(
								querySpec,
								forUpdate,
								followOnLocking
						);
						switch ( lockStrategy ) {
							case CLAUSE:
								renderForUpdateClause( querySpec, forUpdate );
								break;
							case FOLLOW_ON:
								lockOptions = null;
								break;
						}
					}
				}
				forUpdate = null;
			}
			else {
				// Since we get here, we know that no alias locks were applied.
				// We only apply locking on the root query though if there is a global lock mode
				final LockOptions lockOptions = getLockOptions();
				final Boolean followOnLocking = lockOptions.getFollowOnLocking();
				if ( Boolean.TRUE.equals( followOnLocking ) ) {
					this.lockOptions = null;
				}
				else if ( lockOptions.getLockMode() != LockMode.NONE ) {
					final ForUpdateClause forUpdateClause = new ForUpdateClause();
					forUpdateClause.merge( getLockOptions() );
					forUpdateClause.applyAliases( dialect.getWriteRowLockStrategy(), querySpec );
					if ( LockMode.READ.lessThan( forUpdateClause.getLockMode() ) ) {
						final LockStrategy lockStrategy = determineLockingStrategy(
								querySpec,
								forUpdateClause,
								followOnLocking
						);
						switch ( lockStrategy ) {
							case CLAUSE:
								renderForUpdateClause(
										querySpec,
										forUpdateClause
								);
								break;
							case FOLLOW_ON:
								if ( Boolean.FALSE.equals( followOnLocking ) ) {
									throw new UnsupportedOperationException( "" );
								}
								this.lockOptions = null;
								break;
						}
					}
				}
			}
		}
		else if ( forUpdate != null ) {
			forUpdate.merge( getLockOptions() );
			forUpdate.applyAliases( dialect.getWriteRowLockStrategy(), querySpec );
			if ( LockMode.READ.lessThan( forUpdate.getLockMode() ) ) {
				final LockStrategy lockStrategy = determineLockingStrategy( querySpec, forUpdate, null );
				switch ( lockStrategy ) {
					case CLAUSE:
						renderForUpdateClause( querySpec, forUpdate );
						break;
					case FOLLOW_ON:
						throw new UnsupportedOperationException( "Follow-on locking for subqueries is not supported" );
				}
			}
			forUpdate = null;
		}
	}

	protected void renderForUpdateClause(QuerySpec querySpec, ForUpdateClause forUpdateClause) {
		int timeoutMillis = forUpdateClause.getTimeoutMillis();
		LockKind lockKind = LockKind.NONE;
		switch ( forUpdateClause.getLockMode() ) {
			//noinspection deprecation
			case UPGRADE:
				timeoutMillis = LockOptions.WAIT_FOREVER;
			case PESSIMISTIC_WRITE:
				lockKind = LockKind.UPDATE;
				break;
			case PESSIMISTIC_READ:
				lockKind = LockKind.SHARE;
				break;
			case UPGRADE_NOWAIT:
				//noinspection deprecation
			case FORCE:
			case PESSIMISTIC_FORCE_INCREMENT:
				timeoutMillis = LockOptions.NO_WAIT;
				lockKind = LockKind.UPDATE;
				break;
			case UPGRADE_SKIPLOCKED:
				timeoutMillis = LockOptions.SKIP_LOCKED;
				lockKind = LockKind.UPDATE;
				break;
			default:
				break;
		}
		if ( lockKind != LockKind.NONE ) {
			if ( lockKind == LockKind.SHARE ) {
				appendSql( getForShare() );
				if ( forUpdateClause.hasAliases() && getDialect().getReadRowLockStrategy() != RowLockStrategy.NONE ) {
					appendSql( " of " );
					forUpdateClause.appendAliases( this );
				}
			}
			else {
				appendSql( getForUpdate() );
				if ( forUpdateClause.hasAliases() && getDialect().getWriteRowLockStrategy() != RowLockStrategy.NONE ) {
					appendSql( " of " );
					forUpdateClause.appendAliases( this );
				}
			}
			appendSql( getForUpdateWithClause() );
			switch ( timeoutMillis ) {
				case LockOptions.NO_WAIT:
					if ( getDialect().supportsNoWait() ) {
						appendSql( getNoWait() );
					}
					break;
				case LockOptions.SKIP_LOCKED:
					if ( getDialect().supportsSkipLocked() ) {
						appendSql( getSkipLocked() );
					}
					break;
				case LockOptions.WAIT_FOREVER:
					break;
				default:
					if ( getDialect().supportsWait() ) {
						appendSql( " wait " );
						appendSql( Integer.toString( Math.round( timeoutMillis / 1e3f ) ) );
					}
					break;
			}
		}
	}

	private enum LockKind {
		NONE,
		SHARE,
		UPDATE;
	}

	protected String getForUpdate() {
		return " for update";
	}

	protected String getForShare() {
		return " for update";
	}

	protected String getForUpdateWithClause() {
		// This is a clause to specify the lock isolation for e.g. Derby
		return "";
	}

	protected String getNoWait() {
		return " nowait";
	}

	protected String getSkipLocked() {
		return " skip locked";
	}

	protected LockMode getEffectiveLockMode(String alias) {
		final QueryPart currentQueryPart = getQueryPartStack().getCurrent();
		LockMode lockMode = getLockOptions().getAliasSpecificLockMode( alias );
		if ( currentQueryPart.isRoot() && lockMode == null ) {
			lockMode = getLockOptions().getLockMode();
		}
		return lockMode == null ? LockMode.NONE : lockMode;
	}

	protected int getEffectiveLockTimeout(LockMode lockMode) {
		int timeoutMillis = getLockOptions().getTimeOut();
		switch ( lockMode ) {
			//noinspection deprecation
			case UPGRADE:
				timeoutMillis = LockOptions.WAIT_FOREVER;
				break;
			case UPGRADE_NOWAIT:
				//noinspection deprecation
			case FORCE:
			case PESSIMISTIC_FORCE_INCREMENT:
				timeoutMillis = LockOptions.NO_WAIT;
				break;
			case UPGRADE_SKIPLOCKED:
				timeoutMillis = LockOptions.SKIP_LOCKED;
				break;
			default:
				break;
		}
		return timeoutMillis;
	}

	protected boolean hasAggregateFunctions(QuerySpec querySpec) {
		return AggregateFunctionChecker.hasAggregateFunctions( querySpec );
	}

	protected LockStrategy determineLockingStrategy(
			QuerySpec querySpec,
			ForUpdateClause forUpdateClause,
			Boolean followOnLocking) {
		LockStrategy strategy = LockStrategy.CLAUSE;
		if ( !querySpec.getGroupByClauseExpressions().isEmpty() ) {
			if ( Boolean.FALSE.equals( followOnLocking ) ) {
				throw new IllegalQueryOperationException( "Locking with GROUP BY is not supported!" );
			}
			strategy = LockStrategy.FOLLOW_ON;
		}
		if ( querySpec.getHavingClauseRestrictions() != null ) {
			if ( Boolean.FALSE.equals( followOnLocking ) ) {
				throw new IllegalQueryOperationException( "Locking with HAVING is not supported!" );
			}
			strategy = LockStrategy.FOLLOW_ON;
		}
		if ( querySpec.getSelectClause().isDistinct() ) {
			if ( Boolean.FALSE.equals( followOnLocking ) ) {
				throw new IllegalQueryOperationException( "Locking with DISTINCT is not supported!" );
			}
			strategy = LockStrategy.FOLLOW_ON;
		}
		if ( !getDialect().supportsOuterJoinForUpdate() ) {
			if ( forUpdateClause.hasAliases() ) {
				// Only need to visit the TableGroupJoins for which the alias is registered
				if ( querySpec.getFromClause().queryTableGroupJoins(
						tableGroupJoin -> {
							final TableGroup group = tableGroupJoin.getJoinedGroup();
							if ( forUpdateClause.hasAlias( group.getSourceAlias() ) ) {
								if ( tableGroupJoin.getJoinType() != SqlAstJoinType.INNER && !( group instanceof VirtualTableGroup ) ) {
									if ( Boolean.FALSE.equals( followOnLocking ) ) {
										throw new IllegalQueryOperationException(
												"Locking with OUTER joins is not supported!" );
									}
									return Boolean.TRUE;
								}
							}
							return null;
						}
				) != null ) {
					strategy = LockStrategy.FOLLOW_ON;
				}
			}
			else {
				// Visit TableReferenceJoin and TableGroupJoin to see if all use INNER
				if ( querySpec.getFromClause().queryTableJoins(
						tableJoin -> {
							if ( tableJoin.getJoinType() != SqlAstJoinType.INNER && !( tableJoin.getJoinedNode() instanceof VirtualTableGroup ) ) {
								if ( Boolean.FALSE.equals( followOnLocking ) ) {
									throw new IllegalQueryOperationException(
											"Locking with OUTER joins is not supported!" );
								}
								return Boolean.TRUE;
							}
							return null;
						}
				) != null ) {
					strategy = LockStrategy.FOLLOW_ON;
				}
			}
		}
		if ( hasAggregateFunctions( querySpec ) ) {
			if ( Boolean.FALSE.equals( followOnLocking ) ) {
				throw new IllegalQueryOperationException( "Locking with aggregate functions is not supported!" );
			}
			strategy = LockStrategy.FOLLOW_ON;
		}
		return strategy;
	}

	protected void visitReturningColumns(MutationStatement mutationStatement) {
		final List<ColumnReference> returningColumns = mutationStatement.getReturningColumns();
		final int size = returningColumns.size();
		if ( size == 0 ) {
			return;
		}

		appendSql( " returning " );
		String separator = "";
		for ( int i = 0; i < size; i++ ) {
			appendSql( separator );
			appendSql( returningColumns.get( i ).getColumnExpression() );
			separator = ", ";
		}
	}

	public void visitCteContainer(CteContainer cteContainer) {
		final Collection<CteStatement> cteStatements = cteContainer.getCteStatements();
		if ( cteStatements.isEmpty() ) {
			return;
		}
		appendSql( "with " );

		if ( cteContainer.isWithRecursive() ) {
			appendSql( "recursive " );
		}

		String mainSeparator = "";
		for ( CteStatement cte : cteStatements ) {
			appendSql( mainSeparator );
			appendSql( cte.getCteTable().getTableExpression() );

			appendSql( " (" );

			String separator = "";

			for ( CteColumn cteColumn : cte.getCteTable().getCteColumns() ) {
				appendSql( separator );
				appendSql( cteColumn.getColumnExpression() );
				separator = ", ";
			}

			appendSql( ") as " );

			if ( cte.getMaterialization() != CteMaterialization.UNDEFINED ) {
				renderMaterializationHint( cte.getMaterialization() );
			}

			appendSql( '(' );
			cte.getCteDefinition().accept( this );

			appendSql( ')' );

			renderSearchClause( cte );
			renderCycleClause( cte );

			mainSeparator = ", ";
		}
		appendSql( ' ' );
	}

	protected void renderMaterializationHint(CteMaterialization materialization) {
		// No-op by default
	}

	protected void renderSearchClause(CteStatement cte) {
		String separator;
		if ( cte.getSearchClauseKind() != null ) {
			appendSql( " search " );
			if ( cte.getSearchClauseKind() == CteSearchClauseKind.DEPTH_FIRST ) {
				appendSql( " depth " );
			}
			else {
				appendSql( " breadth " );
			}
			appendSql( " first by " );
			separator = "";
			for ( SearchClauseSpecification searchBySpecification : cte.getSearchBySpecifications() ) {
				appendSql( separator );
				appendSql( searchBySpecification.getCteColumn().getColumnExpression() );
				if ( searchBySpecification.getSortOrder() != null ) {
					if ( searchBySpecification.getSortOrder() == SortOrder.ASCENDING ) {
						appendSql( " asc" );
					}
					else {
						appendSql( " desc" );
					}
					if ( searchBySpecification.getNullPrecedence() != null ) {
						if ( searchBySpecification.getNullPrecedence() == NullPrecedence.FIRST ) {
							appendSql( " nulls first" );
						}
						else {
							appendSql( " nulls last" );
						}
					}
				}
				separator = ", ";
			}
		}
	}

	protected void renderCycleClause(CteStatement cte) {
		String separator;
		if ( cte.getCycleMarkColumn() != null ) {
			appendSql( " cycle " );
			separator = "";
			for ( CteColumn cycleColumn : cte.getCycleColumns() ) {
				appendSql( separator );
				appendSql( cycleColumn.getColumnExpression() );
				separator = ", ";
			}
			appendSql( " set " );
			appendSql( cte.getCycleMarkColumn().getColumnExpression() );
			appendSql( " to '" );
			appendSql( cte.getCycleValue() );
			appendSql( "' default '" );
			appendSql( cte.getNoCycleValue() );
			appendSql( "'" );
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// QuerySpec

	@Override
	public void visitQueryGroup(QueryGroup queryGroup) {
		renderQueryGroup( queryGroup, true );
	}

	protected void renderQueryGroup(QueryGroup queryGroup, boolean renderOrderByAndOffsetFetchClause) {
		final QueryPart queryPartForRowNumbering = this.queryPartForRowNumbering;
		final int queryPartForRowNumberingClauseDepth = this.queryPartForRowNumberingClauseDepth;
		final boolean needsSelectAliases = this.needsSelectAliases;
		try {
			String queryGroupAlias = null;
			// See the field documentation of queryPartForRowNumbering etc. for an explanation about this
			final QueryPart currentQueryPart = queryPartStack.getCurrent();
			if ( currentQueryPart != null && queryPartForRowNumberingClauseDepth != clauseStack.depth() ) {
				this.queryPartForRowNumbering = null;
				this.queryPartForRowNumberingClauseDepth = -1;
				this.needsSelectAliases = false;
			}
			// If we are row numbering the current query group, this means that we can't render the
			// order by and offset fetch clause, so we must do row counting on the query group level
			if ( queryPartForRowNumbering == queryGroup ) {
				this.needsSelectAliases = true;
				queryGroupAlias = "grp_" + queryGroupAliasCounter + "_";
				queryGroupAliasCounter++;
				appendSql( "select " );
				appendSql( queryGroupAlias );
				appendSql( ".* " );
				final SelectClause firstSelectClause = queryGroup.getFirstQuerySpec().getSelectClause();
				final List<SqlSelection> sqlSelections = firstSelectClause.getSqlSelections();
				final int sqlSelectionsSize = sqlSelections.size();
				// We need this synthetic select clause to properly render the ORDER BY within the OVER clause
				// of the row numbering functions
				final SelectClause syntheticSelectClause = new SelectClause( sqlSelectionsSize );
				for ( int i = 0; i < sqlSelectionsSize; i++ ) {
					syntheticSelectClause.addSqlSelection(
							new SqlSelectionImpl(
									i + 1,
									i,
									new ColumnReference(
											queryGroupAlias,
											"c" + i,
											false,
											null,
											null,
											StandardBasicTypes.INTEGER,
											null
									)
							)
					);
				}
				renderRowNumberingSelectItems( syntheticSelectClause, queryPartForRowNumbering );
				appendSql( " from (" );
			}
			queryPartStack.push( queryGroup );
			final List<QueryPart> queryParts = queryGroup.getQueryParts();
			final String setOperatorString = " " + queryGroup.getSetOperator().sqlString() + " ";
			String separator = "";
			for ( int i = 0; i < queryParts.size(); i++ ) {
				appendSql( separator );
				queryParts.get( i ).accept( this );
				separator = setOperatorString;
			}

			if ( renderOrderByAndOffsetFetchClause ) {
				visitOrderBy( queryGroup.getSortSpecifications() );
				visitOffsetFetchClause( queryGroup );
			}
			if ( queryGroupAlias != null ) {
				appendSql( ") " );
				appendSql( queryGroupAlias );
			}
		}
		finally {
			queryPartStack.pop();
			this.queryPartForRowNumbering = queryPartForRowNumbering;
			this.queryPartForRowNumberingClauseDepth = queryPartForRowNumberingClauseDepth;
			this.needsSelectAliases = needsSelectAliases;
		}
	}

	@Override
	public void visitQuerySpec(QuerySpec querySpec) {
		final QueryPart queryPartForRowNumbering = this.queryPartForRowNumbering;
		final int queryPartForRowNumberingClauseDepth = this.queryPartForRowNumberingClauseDepth;
		final boolean needsSelectAliases = this.needsSelectAliases;
		final ForUpdateClause forUpdate = this.forUpdate;
		try {
			this.forUpdate = null;
			// See the field documentation of queryPartForRowNumbering etc. for an explanation about this
			final QueryPart currentQueryPart = queryPartStack.getCurrent();
			if ( currentQueryPart != null && ( queryPartForRowNumbering instanceof QueryGroup || queryPartForRowNumberingClauseDepth != clauseStack.depth() ) ) {
				this.queryPartForRowNumbering = null;
				this.queryPartForRowNumberingClauseDepth = -1;
			}
			String queryGroupAlias = "";
			final boolean needsParenthesis;
			if ( currentQueryPart instanceof QueryGroup ) {
				// We always need query wrapping if we are in a query group and the query part has a fetch clause
				if ( needsParenthesis = querySpec.hasOffsetOrFetchClause() ) {
					// If the parent is a query group with a fetch clause, we must use an alias
					// Some DBMS don't support grouping query expressions and need a select wrapper
//					if ( !supportsSimpleQueryGrouping() || currentQueryPart.hasOffsetOrFetchClause() ) {
//						this.needsSelectAliases = true;
//						queryGroupAlias = " grp_" + queryGroupAliasCounter + "_";
//						queryGroupAliasCounter++;
//						appendSql( "select" );
//						appendSql( queryGroupAlias );
//						appendSql( ".* from " );
//					}
					// todo: remove?
				}
			}
			else {
				needsParenthesis = !querySpec.isRoot();
			}
			queryPartStack.push( querySpec );
			if ( needsParenthesis ) {
				appendSql( '(' );
			}
			visitSelectClause( querySpec.getSelectClause() );
			visitFromClause( querySpec.getFromClause() );
			visitWhereClause( querySpec );
			visitGroupByClause( querySpec, dialect.supportsSelectAliasInGroupByClause() );
			visitHavingClause( querySpec );
			visitOrderBy( querySpec.getSortSpecifications() );
			visitOffsetFetchClause( querySpec );
			// We render the FOR UPDATE clause in the parent query
			if ( queryPartForRowNumbering == null ) {
				visitForUpdateClause( querySpec );
			}

			if ( needsParenthesis ) {
				appendSql( ')' );
				appendSql( queryGroupAlias );
			}
		}
		finally {
			this.queryPartStack.pop();
			this.queryPartForRowNumbering = queryPartForRowNumbering;
			this.queryPartForRowNumberingClauseDepth = queryPartForRowNumberingClauseDepth;
			this.needsSelectAliases = needsSelectAliases;
			if ( queryPartForRowNumbering == null ) {
				this.forUpdate = forUpdate;
			}
		}
	}

	protected boolean supportsSimpleQueryGrouping() {
		return true;
	}

	protected final void visitWhereClause(QuerySpec querySpec) {
		final Predicate whereClauseRestrictions = querySpec.getWhereClauseRestrictions();
		if ( whereClauseRestrictions != null && !whereClauseRestrictions.isEmpty() ) {
			appendSql( " where " );

			clauseStack.push( Clause.WHERE );
			try {
				whereClauseRestrictions.accept( this );
			}
			finally {
				clauseStack.pop();
			}
		}
	}

	protected Expression resolveAliasedExpression(Expression expression) {
		// This can happen when using window functions for emulating the offset/fetch clause of a query group
		// But in that case we always use a SqlSelectionExpression anyway, so this is fine as it doesn't need resolving
		if ( queryPartStack.getCurrent() == null ) {
			assert expression instanceof SqlSelectionExpression;
			return ( (SqlSelectionExpression) expression ).getSelection().getExpression();
		}
		return resolveAliasedExpression(
				queryPartStack.getCurrent().getFirstQuerySpec().getSelectClause().getSqlSelections(),
				expression
		);
	}

	protected Expression resolveAliasedExpression(List<SqlSelection> sqlSelections, Expression expression) {
		if ( expression instanceof Literal ) {
			Object literalValue = ( (Literal) expression ).getLiteralValue();
			if ( literalValue instanceof Integer ) {
				return sqlSelections.get( (Integer) literalValue ).getExpression();
			}
		}
		else if ( expression instanceof SqlSelectionExpression ) {
			return ( (SqlSelectionExpression) expression ).getSelection().getExpression();
		}
		else if ( expression instanceof SqmPathInterpretation<?> ) {
			final Expression sqlExpression = ( (SqmPathInterpretation<?>) expression ).getSqlExpression();
			if ( sqlExpression instanceof SqlSelectionExpression ) {
				return ( (SqlSelectionExpression) sqlExpression ).getSelection().getExpression();
			}
		}
		return expression;
	}

	protected final void visitGroupByClause(QuerySpec querySpec, boolean supportsSelectAliases) {
		final List<Expression> partitionExpressions = querySpec.getGroupByClauseExpressions();
		if ( !partitionExpressions.isEmpty() ) {
			try {
				clauseStack.push( Clause.GROUP );
				appendSql( " group by " );
				visitPartitionExpressions( partitionExpressions, supportsSelectAliases );
			}
			finally {
				clauseStack.pop();
			}
		}
	}

	protected final void visitPartitionByClause(List<Expression> partitionExpressions) {
		if ( !partitionExpressions.isEmpty() ) {
			try {
				clauseStack.push( Clause.PARTITION );
				appendSql( "partition by " );
				visitPartitionExpressions( partitionExpressions, false );
			}
			finally {
				clauseStack.pop();
			}
		}
	}

	protected final void visitPartitionExpressions(List<Expression> partitionExpressions, boolean supportsSelectAliases) {
		String separator = "";
		if ( supportsSelectAliases ) {
			for ( Expression partitionExpression : partitionExpressions ) {
				if ( partitionExpression instanceof SqlTuple ) {
					for ( Expression expression : ( (SqlTuple) partitionExpression ).getExpressions() ) {
						appendSql( separator );
						renderPartitionItem( expression );
						separator = COMA_SEPARATOR;
					}
				}
				else {
					appendSql( separator );
					renderPartitionItem( partitionExpression );
				}
				separator = COMA_SEPARATOR;
			}
		}
		else {
			for ( Expression partitionExpression : partitionExpressions ) {
				if ( partitionExpression instanceof SqlTupleContainer ) {
					for ( Expression expression : ( (SqlTupleContainer) partitionExpression ).getSqlTuple().getExpressions() ) {
						appendSql( separator );
						renderPartitionItem( resolveAliasedExpression( expression ) );
						separator = COMA_SEPARATOR;
					}
				}
				else {
					appendSql( separator );
					renderPartitionItem( resolveAliasedExpression( partitionExpression ) );
				}
				separator = COMA_SEPARATOR;
			}
		}
	}

	protected void renderPartitionItem(Expression expression) {
		if ( expression instanceof Literal ) {
			appendSql( "()" );
		}
		else if ( expression instanceof Summarization ) {
			Summarization summarization = (Summarization) expression;
			appendSql( summarization.getKind().name().toLowerCase() );
			appendSql( OPEN_PARENTHESIS );
			renderCommaSeparated( summarization.getGroupings() );
			appendSql( CLOSE_PARENTHESIS );
		}
		else {
			expression.accept( this );
		}
	}

	protected final void visitHavingClause(QuerySpec querySpec) {
		final Predicate havingClauseRestrictions = querySpec.getHavingClauseRestrictions();
		if ( havingClauseRestrictions != null && !havingClauseRestrictions.isEmpty() ) {
			appendSql( " having " );

			clauseStack.push( Clause.HAVING );
			try {
				havingClauseRestrictions.accept( this );
			}
			finally {
				clauseStack.pop();
			}
		}
	}

	protected void visitOrderBy(List<SortSpecification> sortSpecifications) {
		// If we have a query part for row numbering, there is no need to render the order by clause
		// as that is part of the row numbering window function already, by which we then order by in the outer query
		if ( queryPartForRowNumbering == null ) {
			renderOrderBy( true, sortSpecifications );
		}
	}

	protected void renderOrderBy(boolean addWhitespace, List<SortSpecification> sortSpecifications) {
		if ( sortSpecifications != null && !sortSpecifications.isEmpty() ) {
			if ( addWhitespace ) {
				appendSql( ' ' );
			}
			appendSql( "order by " );

			clauseStack.push( Clause.ORDER );
			try {
				String separator = NO_SEPARATOR;
				for ( SortSpecification sortSpecification : sortSpecifications ) {
					appendSql( separator );
					visitSortSpecification( sortSpecification );
					separator = COMA_SEPARATOR;
				}
			}
			finally {
				clauseStack.pop();
			}
		}
	}


	/**
	 * A tuple comparison like <code>(a, b) &gt; (1, 2)</code> can be emulated through it logical definition: <code>a &gt; 1 or a = 1 and b &gt; 2</code>.
	 * The normal tuple comparison emulation is not very index friendly though because of the top level OR predicate.
	 * Index optimized emulation of tuple comparisons puts an AND predicate on the top level.
	 * The effect of that is, that the database can do an index seek to efficiently find a superset of matching rows.
	 * Generally, it is sufficient to just add a broader predicate like for <code>(a, b) &gt; (1, 2)</code> we add <code>a &gt;= 1 and (..)</code>.
	 * But we can further optimize this if we just remove the non-matching parts from this too broad predicate.
	 * For <code>(a, b, c) &gt; (1, 2, 3)</code> we use the broad predicate <code>a &gt;= 1</code> and then want to remove rows where <code>a = 1 and (b, c) &lt;= (2, 3)</code>
	 */
	protected void emulateTupleComparison(
			final List<? extends SqlAstNode> lhsExpressions,
			final List<? extends SqlAstNode> rhsExpressions,
			ComparisonOperator operator,
			boolean indexOptimized) {
		final boolean isCurrentWhereClause = clauseStack.getCurrent() == Clause.WHERE;
		if ( isCurrentWhereClause ) {
			appendSql( OPEN_PARENTHESIS );
		}

		final int size = lhsExpressions.size();
		assert size == rhsExpressions.size();
		switch ( operator ) {
			case DISTINCT_FROM:
				appendSql( "not " );
			case NOT_DISTINCT_FROM: {
				if ( supportsIntersect() ) {
					appendSql( "exists (select " );
					renderCommaSeparatedSelectExpression( lhsExpressions );
					appendSql( getFromDualForSelectOnly() );
					appendSql( " intersect select " );
					renderCommaSeparatedSelectExpression( rhsExpressions );
					appendSql( getFromDualForSelectOnly() );
					appendSql( ")" );
				}
				else {
					appendSql( "exists (select 1" );
					appendSql( getFromDual() );
					appendSql( " where (" );
					String separator = NO_SEPARATOR;
					for ( int i = 0; i < size; i++ ) {
						appendSql( separator );
						lhsExpressions.get( i ).accept( this );
						appendSql( " = " );
						rhsExpressions.get( i ).accept( this );
						appendSql( " or " );
						lhsExpressions.get( i ).accept( this );
						appendSql( " is null and " );
						rhsExpressions.get( i ).accept( this );
						appendSql( " is null" );
						separator = ") and (";
					}
					appendSql( "))" );
				}
				break;
			}
			case EQUAL:
			case NOT_EQUAL: {
				final String operatorText = operator.sqlText();
				String separator = NO_SEPARATOR;
				for ( int i = 0; i < size; i++ ) {
					appendSql( separator );
					lhsExpressions.get( i ).accept( this );
					appendSql( operatorText );
					rhsExpressions.get( i ).accept( this );
					separator = " and ";
				}
				break;
			}
			case LESS_THAN_OR_EQUAL:
				// Optimized (a, b) <= (1, 2) as: a <= 1 and not (a = 1 and b > 2)
				// Normal    (a, b) <= (1, 2) as: a <  1 or a = 1 and (b <= 2)
			case GREATER_THAN_OR_EQUAL:
				// Optimized (a, b) >= (1, 2) as: a >= 1 and not (a = 1 and b < 2)
				// Normal    (a, b) >= (1, 2) as: a >  1 or a = 1 and (b >= 2)
			case LESS_THAN:
				// Optimized (a, b) <  (1, 2) as: a <= 1 and not (a = 1 and b >= 2)
				// Normal    (a, b) <  (1, 2) as: a <  1 or a = 1 and (b < 2)
			case GREATER_THAN: {
				// Optimized (a, b) >  (1, 2) as: a >= 1 and not (a = 1 and b <= 2)
				// Normal    (a, b) >  (1, 2) as: a >  1 or a = 1 and (b > 2)
				if ( indexOptimized ) {
					lhsExpressions.get( 0 ).accept( this );
					appendSql( operator.broader().sqlText() );
					rhsExpressions.get( 0 ).accept( this );
					appendSql( " and not " );
					final String negatedOperatorText = operator.negated().sqlText();
					emulateTupleComparisonSimple(
							lhsExpressions,
							rhsExpressions,
							negatedOperatorText,
							negatedOperatorText,
							true
					);
				}
				else {
					emulateTupleComparisonSimple(
							lhsExpressions,
							rhsExpressions,
							operator.sharper().sqlText(),
							operator.sqlText(),
							false
					);
				}
				break;
			}
		}

		if ( isCurrentWhereClause ) {
			appendSql( CLOSE_PARENTHESIS );
		}
	}

	protected boolean supportsIntersect() {
		return true;
	}

	protected void renderExpressionsAsSubquery(final List<? extends Expression> expressions) {
		clauseStack.push( Clause.SELECT );

		try {
			appendSql( "select " );

			renderCommaSeparatedSelectExpression( expressions );
			appendSql( getFromDualForSelectOnly() );
		}
		finally {
			clauseStack.pop();
		}
	}

	private void emulateTupleComparisonSimple(
			final List<? extends SqlAstNode> lhsExpressions,
			final List<? extends SqlAstNode> rhsExpressions,
			final String operatorText,
			final String finalOperatorText,
			final boolean optimized) {
		// Render (a, b) OP (1, 2) as: (a OP 1 or a = 1 and b FINAL_OP 2)

		final int size = lhsExpressions.size();
		final int lastIndex = size - 1;

		appendSql( OPEN_PARENTHESIS );
		String separator = NO_SEPARATOR;

		int i;
		if ( optimized ) {
			i = 1;
		}
		else {
			lhsExpressions.get( 0 ).accept( this );
			appendSql( operatorText );
			rhsExpressions.get( 0 ).accept( this );
			separator = " or ";
			i = 1;
		}

		for ( ; i < lastIndex; i++ ) {
			// Render the equals parts
			appendSql( separator );
			lhsExpressions.get( i - 1 ).accept( this );
			appendSql( '=' );
			rhsExpressions.get( i - 1 ).accept( this );

			// Render the actual operator part for the current component
			appendSql( " and (" );
			lhsExpressions.get( i ).accept( this );
			appendSql( operatorText );
			rhsExpressions.get( i ).accept( this );
			separator = " or ";
		}

		// Render the equals parts
		appendSql( separator );
		lhsExpressions.get( lastIndex - 1 ).accept( this );
		appendSql( '=' );
		rhsExpressions.get( lastIndex - 1 ).accept( this );

		// Render the actual operator part for the current component
		appendSql( " and " );
		lhsExpressions.get( lastIndex ).accept( this );
		appendSql( finalOperatorText );
		rhsExpressions.get( lastIndex ).accept( this );

		// Close all opened parenthesis
		for ( i = optimized ? 1 : 0; i < lastIndex; i++ ) {
			appendSql( CLOSE_PARENTHESIS );
		}
	}

	protected void renderSelectSimpleComparison(final List<SqlSelection> lhsExpressions, Expression expression, ComparisonOperator operator) {
		renderComparison( lhsExpressions.get( 0 ).getExpression(), operator, expression );
	}

	protected void renderSelectTupleComparison(final List<SqlSelection> lhsExpressions, SqlTuple tuple, ComparisonOperator operator) {
		renderTupleComparisonStandard( lhsExpressions, tuple, operator );
	}

	protected void renderTupleComparisonStandard(
			final List<SqlSelection> lhsExpressions,
			SqlTuple tuple,
			ComparisonOperator operator) {
		appendSql( OPEN_PARENTHESIS );
		String separator = NO_SEPARATOR;
		for ( SqlSelection lhsExpression : lhsExpressions ) {
			appendSql( separator );
			lhsExpression.getExpression().accept( this );
			separator = COMA_SEPARATOR;
		}
		appendSql( CLOSE_PARENTHESIS );
		appendSql( " " );
		appendSql( operator.sqlText() );
		appendSql( " " );
		tuple.accept( this );
	}

	protected void renderComparison(Expression lhs, ComparisonOperator operator, Expression rhs) {
		renderComparisonStandard( lhs, operator, rhs );
	}

	protected void renderComparisonStandard(Expression lhs, ComparisonOperator operator, Expression rhs) {
		lhs.accept( this );
		appendSql( " " );
		appendSql( operator.sqlText() );
		appendSql( " " );
		rhs.accept( this );
	}

	protected void renderComparisonDistinctOperator(Expression lhs, ComparisonOperator operator, Expression rhs) {
		final boolean notWrapper;
		final String operatorText;
		switch ( operator ) {
			case DISTINCT_FROM:
				notWrapper = true;
				operatorText = "<=>";
				break;
			case NOT_DISTINCT_FROM:
				notWrapper = false;
				operatorText = "<=>";
				break;
			default:
				notWrapper = false;
				operatorText = operator.sqlText();
				break;
		}
		if ( notWrapper ) {
			appendSql( "not(" );
		}
		lhs.accept( this );
		appendSql( ' ' );
		appendSql( operatorText );
		appendSql( ' ' );
		rhs.accept( this );
		if ( notWrapper ) {
			appendSql( ')' );
		}
	}

	protected void renderComparisonEmulateDecode(Expression lhs, ComparisonOperator operator, Expression rhs) {
		switch ( operator ) {
			case DISTINCT_FROM:
				appendSql( "decode(" );
				lhs.accept( this );
				appendSql( ',' );
				rhs.accept( this );
				appendSql( ",0,1)=1" );
				break;
			case NOT_DISTINCT_FROM:
				appendSql( "decode(" );
				lhs.accept( this );
				appendSql( ',' );
				rhs.accept( this );
				appendSql( ",0,1)=0" );
				break;
			default:
				lhs.accept( this );
				appendSql( ' ' );
				appendSql( operator.sqlText() );
				appendSql( ' ' );
				rhs.accept( this );
				break;
		}
	}

	protected void renderComparisonEmulateIntersect(Expression lhs, ComparisonOperator operator, Expression rhs) {
		switch ( operator ) {
			case DISTINCT_FROM:
				appendSql( "not " );
			case NOT_DISTINCT_FROM: {
				appendSql( "exists (select " );
				clauseStack.push( Clause.SELECT );
				renderSelectExpression( lhs );
				appendSql( getFromDualForSelectOnly() );
				appendSql( " intersect select " );
				renderSelectExpression( rhs );
				appendSql( getFromDualForSelectOnly() );
				clauseStack.pop();
				appendSql( ")" );
				return;
			}
		}
		lhs.accept( this );
		appendSql( " " );
		appendSql( operator.sqlText() );
		appendSql( " " );
		rhs.accept( this );
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// ORDER BY clause

	@Override
	public void visitSortSpecification(SortSpecification sortSpecification) {
		final Expression sortExpression = sortSpecification.getSortExpression();
		final NullPrecedence nullPrecedence = sortSpecification.getNullPrecedence();
		final SortOrder sortOrder = sortSpecification.getSortOrder();
		if ( sortExpression instanceof SqlTupleContainer ) {
			final SqlTuple sqlTuple = ( (SqlTupleContainer) sortExpression ).getSqlTuple();
			if ( sqlTuple != null ){
				String separator = NO_SEPARATOR;
				for ( Expression expression : sqlTuple.getExpressions() ) {
					appendSql( separator );
					visitSortSpecification( expression, sortOrder, nullPrecedence );
					separator = COMA_SEPARATOR;
				}
			}
			else {
				visitSortSpecification( sortExpression, sortOrder, nullPrecedence );
			}
		}
		else {
			visitSortSpecification( sortExpression, sortOrder, nullPrecedence );
		}
	}

	protected void visitSortSpecification(Expression sortExpression, SortOrder sortOrder, NullPrecedence nullPrecedence) {
		if ( nullPrecedence == null || nullPrecedence == NullPrecedence.NONE ) {
			nullPrecedence = sessionFactory.getSessionFactoryOptions().getDefaultNullPrecedence();
		}
		final boolean renderNullPrecedence = nullPrecedence != null &&
				!nullPrecedence.isDefaultOrdering( sortOrder, dialect.getNullOrdering() );
		if ( renderNullPrecedence && !dialect.supportsNullPrecedence() ) {
			emulateSortSpecificationNullPrecedence( sortExpression, nullPrecedence );
		}

		if ( inOverClause() ) {
			resolveAliasedExpression( sortExpression ).accept( this );
		}
		else {
			sortExpression.accept( this );
		}

		if ( sortOrder == SortOrder.ASCENDING ) {
			appendSql( " asc" );
		}
		else if ( sortOrder == SortOrder.DESCENDING ) {
			appendSql( " desc" );
		}

		if ( renderNullPrecedence && dialect.supportsNullPrecedence() ) {
			appendSql( " nulls " );
			appendSql( nullPrecedence.name().toLowerCase( Locale.ROOT ) );
		}
	}

	protected void emulateSortSpecificationNullPrecedence(Expression sortExpression, NullPrecedence nullPrecedence) {
		// TODO: generate "virtual" select items and use them here positionally
		appendSql( "case when (" );
		resolveAliasedExpression( sortExpression ).accept( this );
		appendSql( ") is null then " );
		if ( nullPrecedence == NullPrecedence.FIRST ) {
			appendSql( "0 else 1" );
		}
		else {
			appendSql( "1 else 0" );
		}
		appendSql( " end" );
		appendSql( COMA_SEPARATOR );
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// LIMIT/OFFSET/FETCH clause

	@Override
	public void visitOffsetFetchClause(QueryPart queryPart) {
		if ( !isRowNumberingCurrentQueryPart() ) {
			renderOffsetFetchClause( queryPart, true );
		}
	}

	protected void renderOffsetFetchClause(QueryPart queryPart, boolean renderOffsetRowsKeyword) {
		if ( queryPart.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderOffsetFetchClause(
					getOffsetParameter(),
					getLimitParameter(),
					FetchClauseType.ROWS_ONLY,
					renderOffsetRowsKeyword
			);
		}
		else {
			renderOffsetFetchClause(
					queryPart.getOffsetClauseExpression(),
					queryPart.getFetchClauseExpression(),
					queryPart.getFetchClauseType(),
					renderOffsetRowsKeyword
			);
		}
	}

	protected void renderOffsetFetchClause(
			Expression offsetExpression,
			Expression fetchExpression,
			FetchClauseType fetchClauseType,
			boolean renderOffsetRowsKeyword) {
		if ( offsetExpression != null ) {
			renderOffset( offsetExpression, renderOffsetRowsKeyword );
		}

		if ( fetchExpression != null ) {
			renderFetch( fetchExpression, null, fetchClauseType );
		}
	}

	@SuppressWarnings("WeakerAccess")
	protected void renderOffset(Expression offsetExpression, boolean renderOffsetRowsKeyword) {
		appendSql( " offset " );
		clauseStack.push( Clause.OFFSET );
		try {
			renderOffsetExpression( offsetExpression );
		}
		finally {
			clauseStack.pop();
		}
		if ( renderOffsetRowsKeyword ) {
			appendSql( " rows" );
		}
	}

	@SuppressWarnings("WeakerAccess")
	protected void renderFetch(
			Expression fetchExpression,
			Expression offsetExpressionToAdd,
			FetchClauseType fetchClauseType) {
		appendSql( " fetch first " );
		clauseStack.push( Clause.FETCH );
		try {
			if ( offsetExpressionToAdd == null ) {
				renderFetchExpression( fetchExpression );
			}
			else {
				renderFetchPlusOffsetExpression( fetchExpression, offsetExpressionToAdd, 0 );
			}
		}
		finally {
			clauseStack.pop();
		}
		switch ( fetchClauseType ) {
			case ROWS_ONLY:
				appendSql( " rows only" );
				break;
			case ROWS_WITH_TIES:
				appendSql( " rows with ties" );
				break;
			case PERCENT_ONLY:
				appendSql( " percent rows only" );
				break;
			case PERCENT_WITH_TIES:
				appendSql( " percent rows with ties" );
				break;
		}
	}

	protected void renderOffsetExpression(Expression offsetExpression) {
		offsetExpression.accept( this );
	}

	protected void renderFetchExpression(Expression fetchExpression) {
		fetchExpression.accept( this );
	}

	protected void renderTopClause(QuerySpec querySpec, boolean addOffset, boolean needsParenthesis) {
		if ( querySpec.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderTopClause(
					getOffsetParameter(),
					getLimitParameter(),
					FetchClauseType.ROWS_ONLY,
					addOffset,
					needsParenthesis
			);
		}
		else {
			renderTopClause(
					querySpec.getOffsetClauseExpression(),
					querySpec.getFetchClauseExpression(),
					querySpec.getFetchClauseType(),
					addOffset,
					needsParenthesis
			);
		}
	}

	protected void renderTopClause(
			Expression offsetExpression,
			Expression fetchExpression,
			FetchClauseType fetchClauseType,
			boolean addOffset,
			boolean needsParenthesis) {
		if ( fetchExpression != null ) {
			appendSql( "top " );
			if ( needsParenthesis ) {
				appendSql( '(' );
			}
			final Stack<Clause> clauseStack = getClauseStack();
			clauseStack.push( Clause.FETCH );
			try {
				if ( addOffset && offsetExpression != null ) {
					renderFetchPlusOffsetExpression( fetchExpression, offsetExpression, 0 );
				}
				else {
					renderFetchExpression( fetchExpression );
				}
			}
			finally {
				clauseStack.pop();
			}
			if ( needsParenthesis ) {
				appendSql( ')' );
			}
			appendSql( ' ' );
			switch ( fetchClauseType ) {
				case ROWS_WITH_TIES:
					appendSql( "with ties " );
					break;
				case PERCENT_ONLY:
					appendSql( "percent " );
					break;
				case PERCENT_WITH_TIES:
					appendSql( "percent with ties " );
					break;
			}
		}
	}

	protected void renderTopStartAtClause(QuerySpec querySpec) {
		if ( querySpec.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderTopStartAtClause( getOffsetParameter(), getLimitParameter(), FetchClauseType.ROWS_ONLY );
		}
		else {
			renderTopStartAtClause(
					querySpec.getOffsetClauseExpression(),
					querySpec.getFetchClauseExpression(),
					querySpec.getFetchClauseType()
			);
		}
	}

	protected void renderTopStartAtClause(
			Expression offsetExpression,
			Expression fetchExpression,
			FetchClauseType fetchClauseType) {
		if ( fetchExpression != null ) {
			appendSql( "top " );
			final Stack<Clause> clauseStack = getClauseStack();
			clauseStack.push( Clause.FETCH );
			try {
				renderFetchExpression( fetchExpression );
			}
			finally {
				clauseStack.pop();
			}
			if ( offsetExpression != null ) {
				clauseStack.push( Clause.OFFSET );
				try {
					appendSql( " start at " );
					renderOffsetExpression( offsetExpression );
				}
				finally {
					clauseStack.pop();
				}
			}
			appendSql( ' ' );
			switch ( fetchClauseType ) {
				case ROWS_WITH_TIES:
					appendSql( "with ties " );
					break;
				case PERCENT_ONLY:
					appendSql( "percent " );
					break;
				case PERCENT_WITH_TIES:
					appendSql( "percent with ties " );
					break;
			}
		}
	}

	protected void renderRowsToClause(QuerySpec querySpec) {
		if ( querySpec.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderRowsToClause( getOffsetParameter(), getLimitParameter() );
		}
		else {
			assertRowsOnlyFetchClauseType( querySpec );
			renderRowsToClause( querySpec.getOffsetClauseExpression(), querySpec.getFetchClauseExpression() );
		}
	}

	protected void renderRowsToClause(Expression offsetClauseExpression, Expression fetchClauseExpression) {
		if ( fetchClauseExpression != null ) {
			appendSql( "rows " );
			final Stack<Clause> clauseStack = getClauseStack();
			clauseStack.push( Clause.FETCH );
			try {
				renderFetchExpression( fetchClauseExpression );
			}
			finally {
				clauseStack.pop();
			}
			if ( offsetClauseExpression != null ) {
				clauseStack.push( Clause.OFFSET );
				try {
					appendSql( " to " );
					// According to RowsLimitHandler this is 1 based so we need to add 1 to the offset
					renderFetchPlusOffsetExpression( fetchClauseExpression, offsetClauseExpression, 1 );
				}
				finally {
					clauseStack.pop();
				}
			}
			appendSql( ' ' );
		}
	}

	protected void renderFetchPlusOffsetExpression(
			Expression fetchClauseExpression,
			Expression offsetClauseExpression,
			int offset) {
		renderFetchExpression( fetchClauseExpression );
		appendSql( '+' );
		renderOffsetExpression( offsetClauseExpression );
		if ( offset != 0 ) {
			appendSql( '+' );
			appendSql( Integer.toString( offset ) );
		}
	}

	protected void renderFetchPlusOffsetExpressionAsLiteral(
			Expression fetchClauseExpression,
			Expression offsetClauseExpression,
			int offset) {
		final Number offsetCount = interpretExpression( offsetClauseExpression, jdbcParameterBindings );
		final Number fetchCount = interpretExpression( fetchClauseExpression, jdbcParameterBindings );
		appendSql( Integer.toString( fetchCount.intValue() + offsetCount.intValue() + offset ) );
	}

	protected void renderFetchPlusOffsetExpressionAsSingleParameter(
			Expression fetchClauseExpression,
			Expression offsetClauseExpression,
			int offset) {
		if ( fetchClauseExpression instanceof Literal ) {
			final Number fetchCount = (Number) ( (Literal) fetchClauseExpression ).getLiteralValue();
			if ( offsetClauseExpression instanceof Literal ) {
				final Number offsetCount = (Number) ( (Literal) offsetClauseExpression ).getLiteralValue();
				appendSql( Integer.toString( fetchCount.intValue() + offsetCount.intValue() + offset ) );
			}
			else {
				appendSql( PARAM_MARKER );
				final JdbcParameter offsetParameter = (JdbcParameter) offsetClauseExpression;
				final int offsetValue = offset + fetchCount.intValue();
				jdbcParameters.addParameter( offsetParameter );
				parameterBinders.add(
						(statement, startPosition, jdbcParameterBindings, executionContext) -> {
							final JdbcParameterBinding binding = jdbcParameterBindings.getBinding( offsetParameter );
							if ( binding == null ) {
								throw new ExecutionException( "JDBC parameter value not bound - " + offsetParameter );
							}
							final Number bindValue = (Number) binding.getBindValue();
							offsetParameter.getExpressionType().getJdbcMappings().get( 0 ).getJdbcValueBinder().bind(
									statement,
									bindValue.intValue() + offsetValue,
									startPosition,
									executionContext.getSession()
							);
						}
				);
			}
		}
		else {
			appendSql( PARAM_MARKER );
			final JdbcParameter offsetParameter = (JdbcParameter) offsetClauseExpression;
			final JdbcParameter fetchParameter = (JdbcParameter) fetchClauseExpression;
			final OffsetReceivingParameterBinder fetchBinder = new OffsetReceivingParameterBinder(
					offsetParameter,
					fetchParameter,
					offset
			);
			// We don't register and bind the special OffsetJdbcParameter as that comes from the query options
			// And in this case, we only want to bind a single JDBC parameter
			if ( !( offsetParameter instanceof OffsetJdbcParameter ) ) {
				jdbcParameters.addParameter( offsetParameter );
				parameterBinders.add(
						(statement, startPosition, jdbcParameterBindings, executionContext) -> {
							final JdbcParameterBinding binding = jdbcParameterBindings.getBinding( offsetParameter );
							if ( binding == null ) {
								throw new ExecutionException( "JDBC parameter value not bound - " + offsetParameter );
							}
							fetchBinder.dynamicOffset = (Number) binding.getBindValue();
						}
				);
			}
			jdbcParameters.addParameter( fetchParameter );
			parameterBinders.add( fetchBinder );
		}
	}

	private static class OffsetReceivingParameterBinder implements JdbcParameterBinder {

		private final JdbcParameter offsetParameter;
		private final JdbcParameter fetchParameter;
		private final int staticOffset;
		private Number dynamicOffset;

		public OffsetReceivingParameterBinder(
				JdbcParameter offsetParameter,
				JdbcParameter fetchParameter,
				int staticOffset) {
			this.offsetParameter = offsetParameter;
			this.fetchParameter = fetchParameter;
			this.staticOffset = staticOffset;
		}

		@Override
		public void bindParameterValue(
				PreparedStatement statement,
				int startPosition,
				JdbcParameterBindings jdbcParameterBindings,
				ExecutionContext executionContext) throws SQLException {
			final Number bindValue;
			if ( fetchParameter instanceof LimitJdbcParameter ) {
				bindValue = executionContext.getQueryOptions().getEffectiveLimit().getMaxRows();
			}
			else {
				final JdbcParameterBinding binding = jdbcParameterBindings.getBinding( fetchParameter );
				if ( binding == null ) {
					throw new ExecutionException( "JDBC parameter value not bound - " + fetchParameter );
				}
				bindValue = (Number) binding.getBindValue();
			}
			final int offsetValue;
			if ( offsetParameter instanceof OffsetJdbcParameter ) {
				offsetValue = executionContext.getQueryOptions().getEffectiveLimit().getFirstRow();
			}
			else {
				offsetValue = dynamicOffset.intValue() + staticOffset;
				dynamicOffset = null;
			}
			fetchParameter.getExpressionType().getJdbcMappings().get( 0 ).getJdbcValueBinder().bind(
					statement,
					bindValue.intValue() + offsetValue,
					startPosition,
					executionContext.getSession()
			);
		}
	}

	protected void renderFirstSkipClause(QuerySpec querySpec) {
		if ( querySpec.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderFirstSkipClause( getOffsetParameter(), getLimitParameter() );
		}
		else {
			assertRowsOnlyFetchClauseType( querySpec );
			renderFirstSkipClause( querySpec.getOffsetClauseExpression(), querySpec.getFetchClauseExpression() );
		}
	}

	protected void renderFirstSkipClause(Expression offsetExpression, Expression fetchExpression) {
		final Stack<Clause> clauseStack = getClauseStack();
		if ( fetchExpression != null ) {
			appendSql( "first " );
			clauseStack.push( Clause.FETCH );
			try {
				renderFetchExpression( fetchExpression );
			}
			finally {
				clauseStack.pop();
			}
			appendSql( ' ' );
		}
		if ( offsetExpression != null ) {
			appendSql( "skip " );
			clauseStack.push( Clause.OFFSET );
			try {
				renderOffsetExpression( offsetExpression );
			}
			finally {
				clauseStack.pop();
			}
			appendSql( ' ' );
		}
	}

	protected void renderSkipFirstClause(QuerySpec querySpec) {
		if ( querySpec.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderSkipFirstClause( getOffsetParameter(), getLimitParameter() );
		}
		else {
			assertRowsOnlyFetchClauseType( querySpec );
			renderSkipFirstClause( querySpec.getOffsetClauseExpression(), querySpec.getFetchClauseExpression() );
		}
	}

	protected void renderSkipFirstClause(Expression offsetExpression, Expression fetchExpression) {
		final Stack<Clause> clauseStack = getClauseStack();
		if ( offsetExpression != null ) {
			appendSql( "skip " );
			clauseStack.push( Clause.OFFSET );
			try {
				renderOffsetExpression( offsetExpression );
			}
			finally {
				clauseStack.pop();
			}
			appendSql( ' ' );
		}
		if ( fetchExpression != null ) {
			appendSql( "first " );
			clauseStack.push( Clause.FETCH );
			try {
				renderFetchExpression( fetchExpression );
			}
			finally {
				clauseStack.pop();
			}
			appendSql( ' ' );
		}
	}

	protected void renderFirstClause(QuerySpec querySpec) {
		if ( querySpec.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderFirstClause( getOffsetParameter(), getLimitParameter() );
		}
		else {
			assertRowsOnlyFetchClauseType( querySpec );
			renderFirstClause( querySpec.getOffsetClauseExpression(), querySpec.getFetchClauseExpression() );
		}
	}

	protected void renderFirstClause(Expression offsetExpression, Expression fetchExpression) {
		final Stack<Clause> clauseStack = getClauseStack();
		if ( fetchExpression != null ) {
			appendSql( "first " );
			clauseStack.push( Clause.FETCH );
			try {
				renderFetchPlusOffsetExpression( fetchExpression, offsetExpression, 0 );
			}
			finally {
				clauseStack.pop();
			}
			appendSql( ' ' );
		}
	}

	protected void renderCombinedLimitClause(QueryPart queryPart) {
		if ( queryPart.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderCombinedLimitClause( getOffsetParameter(), getLimitParameter() );
		}
		else {
			assertRowsOnlyFetchClauseType( queryPart );
			renderCombinedLimitClause( queryPart.getOffsetClauseExpression(), queryPart.getFetchClauseExpression() );
		}
	}

	protected void renderCombinedLimitClause(Expression offsetExpression, Expression fetchExpression) {
		if ( offsetExpression != null ) {
			final Stack<Clause> clauseStack = getClauseStack();
			appendSql( " limit " );
			clauseStack.push( Clause.OFFSET );
			try {
				renderOffsetExpression( offsetExpression );
			}
			finally {
				clauseStack.pop();
			}
			appendSql( COMA_SEPARATOR );
			if ( fetchExpression != null ) {
				clauseStack.push( Clause.FETCH );
				try {
					renderFetchExpression( fetchExpression );
				}
				finally {
					clauseStack.pop();
				}
			}
			else {
				appendSql( Integer.toString( Integer.MAX_VALUE ) );
			}
		}
		else if ( fetchExpression != null ) {
			final Stack<Clause> clauseStack = getClauseStack();
			appendSql( " limit " );
			clauseStack.push( Clause.FETCH );
			try {
				renderFetchExpression( fetchExpression );
			}
			finally {
				clauseStack.pop();
			}
		}
	}

	protected void renderLimitOffsetClause(QueryPart queryPart) {
		if ( queryPart.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			renderLimitOffsetClause( getOffsetParameter(), getLimitParameter() );
		}
		else {
			assertRowsOnlyFetchClauseType( queryPart );
			renderLimitOffsetClause( queryPart.getOffsetClauseExpression(), queryPart.getFetchClauseExpression() );
		}
	}

	protected void renderLimitOffsetClause(Expression offsetExpression, Expression fetchExpression) {
		if ( fetchExpression != null ) {
			appendSql( " limit " );
			clauseStack.push( Clause.FETCH );
			try {
				renderFetchExpression( fetchExpression );
			}
			finally {
				clauseStack.pop();
			}
		}
		else if ( offsetExpression != null ) {
			appendSql( " limit " );
			appendSql( Integer.toString( Integer.MAX_VALUE ) );
		}
		if ( offsetExpression != null ) {
			final Stack<Clause> clauseStack = getClauseStack();
			appendSql( " offset " );
			clauseStack.push( Clause.OFFSET );
			try {
				renderOffsetExpression( offsetExpression );
			}
			finally {
				clauseStack.pop();
			}
		}
	}

	protected void assertRowsOnlyFetchClauseType(QueryPart queryPart) {
		if ( !queryPart.isRoot() || !hasLimit() ) {
			final FetchClauseType fetchClauseType = queryPart.getFetchClauseType();
			if ( fetchClauseType != null && fetchClauseType != FetchClauseType.ROWS_ONLY ) {
				throw new IllegalArgumentException( "Can't emulate fetch clause type: " + fetchClauseType );
			}
		}
	}

	protected QueryPart getQueryPartForRowNumbering() {
		return queryPartForRowNumbering;
	}

	protected boolean isRowNumberingCurrentQueryPart() {
		return queryPartForRowNumbering != null;
	}

	protected void emulateFetchOffsetWithWindowFunctions(QueryPart queryPart, boolean emulateFetchClause) {
		if ( queryPart.isRoot() && hasLimit() ) {
			prepareLimitOffsetParameters();
			emulateFetchOffsetWithWindowFunctions(
					queryPart,
					getOffsetParameter(),
					getLimitParameter(),
					FetchClauseType.ROWS_ONLY,
					emulateFetchClause
			);
		}
		else {
			emulateFetchOffsetWithWindowFunctions(
					queryPart,
					queryPart.getOffsetClauseExpression(),
					queryPart.getFetchClauseExpression(),
					queryPart.getFetchClauseType(),
					emulateFetchClause
			);
		}
	}

	protected void emulateFetchOffsetWithWindowFunctions(
			QueryPart queryPart,
			Expression offsetExpression,
			Expression fetchExpression,
			FetchClauseType fetchClauseType,
			boolean emulateFetchClause) {
		final QueryPart queryPartForRowNumbering = this.queryPartForRowNumbering;
		final int queryPartForRowNumberingClauseDepth = this.queryPartForRowNumberingClauseDepth;
		final boolean needsSelectAliases = this.needsSelectAliases;
		try {
			this.queryPartForRowNumbering = queryPart;
			this.queryPartForRowNumberingClauseDepth = clauseStack.depth();
			this.needsSelectAliases = true;
			final String alias = "r_" + queryPartForRowNumberingAliasCounter + "_";
			queryPartForRowNumberingAliasCounter++;
			final boolean needsParenthesis;
			if ( queryPart instanceof QueryGroup ) {
				// We always need query wrapping if we are in a query group and the query part has a fetch clause
				needsParenthesis = queryPart.hasOffsetOrFetchClause();
			}
			else {
				needsParenthesis = !queryPart.isRoot();
			}
			if ( needsParenthesis && !queryPart.isRoot() ) {
				appendSql( '(' );
			}
			appendSql( "select " );
			if ( getClauseStack().isEmpty() ) {
				appendSql( "*" );
			}
			else {
				final int size = queryPart.getFirstQuerySpec().getSelectClause().getSqlSelections().size();
				String separator = "";
				for ( int i = 0; i < size; i++ ) {
					appendSql( separator );
					appendSql( alias );
					appendSql( ".c" );
					appendSql( Integer.toString( i ) );
					separator = COMA_SEPARATOR;
				}
			}
			appendSql( " from " );
			if ( !needsParenthesis || queryPart.isRoot() ) {
				appendSql( '(' );
			}
			queryPart.accept( this );
			if ( !needsParenthesis || queryPart.isRoot() ) {
				appendSql( ')' );
			}
			appendSql( ' ');
			appendSql( alias );
			appendSql( " where " );
			final Stack<Clause> clauseStack = getClauseStack();
			clauseStack.push( Clause.WHERE );
			try {
				if ( emulateFetchClause && fetchExpression != null ) {
					switch ( fetchClauseType ) {
						case PERCENT_ONLY:
							appendSql( alias );
							appendSql( ".rn <= " );
							if ( offsetExpression != null ) {
								offsetExpression.accept( this );
								appendSql( " + " );
							}
							appendSql( "ceil(");
							appendSql( alias );
							appendSql( ".cnt * " );
							fetchExpression.accept( this );
							appendSql( " / 100)" );
							break;
						case ROWS_ONLY:
							appendSql( alias );
							appendSql( ".rn <= " );
							if ( offsetExpression != null ) {
								offsetExpression.accept( this );
								appendSql( " + " );
							}
							fetchExpression.accept( this );
							break;
						case PERCENT_WITH_TIES:
							appendSql( alias );
							appendSql( ".rnk <= " );
							if ( offsetExpression != null ) {
								offsetExpression.accept( this );
								appendSql( " + " );
							}
							appendSql( "ceil(");
							appendSql( alias );
							appendSql( ".cnt * " );
							fetchExpression.accept( this );
							appendSql( " / 100)" );
							break;
						case ROWS_WITH_TIES:
							appendSql( alias );
							appendSql( ".rnk <= " );
							if ( offsetExpression != null ) {
								offsetExpression.accept( this );
								appendSql( " + " );
							}
							fetchExpression.accept( this );
							break;
					}
				}
				// todo: not sure if databases handle order by row number or the original ordering better..
				if ( offsetExpression == null ) {
					if ( queryPart.isRoot() ) {
						switch ( fetchClauseType ) {
							case PERCENT_ONLY:
							case ROWS_ONLY:
								appendSql( " order by " );
								appendSql( alias );
								appendSql( ".rn" );
								break;
							case PERCENT_WITH_TIES:
							case ROWS_WITH_TIES:
								appendSql( " order by " );
								appendSql( alias );
								appendSql( ".rnk" );
								break;
						}
					}
				}
				else {
					if ( emulateFetchClause && fetchExpression != null ) {
						appendSql( " and " );
					}
					appendSql( alias );
					appendSql( ".rn > " );
					offsetExpression.accept( this );
					if ( queryPart.isRoot() ) {
						appendSql( " order by " );
						appendSql( alias );
						appendSql( ".rn" );
					}
				}

				// We render the FOR UPDATE clause in the outer query
				if ( queryPart instanceof QuerySpec ) {
					clauseStack.pop();
					clauseStack.push( Clause.FOR_UPDATE );
					visitForUpdateClause( (QuerySpec) queryPart );
				}
			}
			finally {
				clauseStack.pop();
			}
			if ( needsParenthesis && !queryPart.isRoot() ) {
				appendSql( ')' );
			}
		}
		finally {
			this.queryPartForRowNumbering = queryPartForRowNumbering;
			this.queryPartForRowNumberingClauseDepth = queryPartForRowNumberingClauseDepth;
			this.needsSelectAliases = needsSelectAliases;
		}
	}

	protected final void withRowNumbering(QueryPart queryPart, Runnable r) {
		final QueryPart queryPartForRowNumbering = this.queryPartForRowNumbering;
		final int queryPartForRowNumberingClauseDepth = this.queryPartForRowNumberingClauseDepth;
		final boolean needsSelectAliases = this.needsSelectAliases;
		try {
			this.queryPartForRowNumbering = queryPart;
			this.queryPartForRowNumberingClauseDepth = clauseStack.depth();
			this.needsSelectAliases = false;
			r.run();
		}
		finally {
			this.queryPartForRowNumbering = queryPartForRowNumbering;
			this.queryPartForRowNumberingClauseDepth = queryPartForRowNumberingClauseDepth;
			this.needsSelectAliases = needsSelectAliases;
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// SELECT clause

	@Override
	public void visitSelectClause(SelectClause selectClause) {
		clauseStack.push( Clause.SELECT );

		try {
			appendSql( "select " );
			if ( selectClause.isDistinct() ) {
				appendSql( "distinct " );
			}
			visitSqlSelections( selectClause );
		}
		finally {
			clauseStack.pop();
		}
	}

	protected void visitSqlSelections(SelectClause selectClause) {
		final List<SqlSelection> sqlSelections = selectClause.getSqlSelections();
		final int size = sqlSelections.size();
		if ( needsSelectAliases ) {
			String separator = NO_SEPARATOR;
			for ( int i = 0; i < size; i++ ) {
				final SqlSelection sqlSelection = sqlSelections.get( i );
				appendSql( separator );
				visitSqlSelection( sqlSelection );
				appendSql( " c" );
				appendSql( Integer.toString( i ) );
				separator = COMA_SEPARATOR;
			}
			if ( queryPartForRowNumbering != null ) {
				renderRowNumberingSelectItems( selectClause, queryPartForRowNumbering );
			}
		}
		else {
			String separator = NO_SEPARATOR;
			for ( int i = 0; i < size; i++ ) {
				final SqlSelection sqlSelection = sqlSelections.get( i );
				appendSql( separator );
				visitSqlSelection( sqlSelection );
				separator = COMA_SEPARATOR;
			}
		}
	}

	protected void renderRowNumberingSelectItems(SelectClause selectClause, QueryPart queryPart) {
		final FetchClauseType fetchClauseType = getFetchClauseTypeForRowNumbering( queryPart );
		if ( fetchClauseType != null ) {
			appendSql( COMA_SEPARATOR );
			switch ( fetchClauseType ) {
				case PERCENT_ONLY:
					appendSql( "count(*) over () cnt," );
				case ROWS_ONLY:
					renderRowNumber( selectClause, queryPart );
					appendSql( " rn" );
					break;
				case PERCENT_WITH_TIES:
					appendSql( "count(*) over () cnt," );
				case ROWS_WITH_TIES:
					if ( queryPart.getOffsetClauseExpression() != null ) {
						renderRowNumber( selectClause, queryPart );
						appendSql( " rn, " );
					}
					if ( selectClause.isDistinct() ) {
						appendSql( "dense_rank()" );
					}
					else {
						appendSql( "rank()" );
					}
					visitOverClause(
							Collections.emptyList(),
							getSortSpecificationsRowNumbering( selectClause, queryPart )
					);
					appendSql( " rnk" );
					break;
			}
		}
	}

	protected FetchClauseType getFetchClauseTypeForRowNumbering(QueryPart queryPartForRowNumbering) {
		if ( queryPartForRowNumbering.isRoot() && hasLimit() ) {
			return FetchClauseType.ROWS_ONLY;
		}
		else {
			return queryPartForRowNumbering.getFetchClauseType();
		}
	}

	protected void visitOverClause(
			List<Expression> partitionExpressions,
			List<SortSpecification> sortSpecifications) {
		try {
			clauseStack.push( Clause.OVER );
			appendSql( " over (" );
			visitPartitionByClause( partitionExpressions );
			renderOrderBy( !partitionExpressions.isEmpty(), sortSpecifications );
			appendSql( ')' );
		}
		finally {
			clauseStack.pop();
		}
	}

	protected void renderRowNumber(SelectClause selectClause, QueryPart queryPart) {
		if ( selectClause.isDistinct() ) {
			appendSql( "dense_rank()" );
		}
		else {
			appendSql( "row_number()" );
		}
		visitOverClause( Collections.emptyList(), getSortSpecificationsRowNumbering( selectClause, queryPart ) );
	}

	protected List<SortSpecification> getSortSpecificationsRowNumbering(
			SelectClause selectClause,
			QueryPart queryPart) {
		final List<SortSpecification> sortSpecifications;
		if ( queryPart.hasSortSpecifications() ) {
			sortSpecifications = queryPart.getSortSpecifications();
		}
		else {
			sortSpecifications = Collections.emptyList();
		}
		if ( selectClause.isDistinct() ) {
			// When select distinct is used, we need to add all select items to the order by clause
			final List<SqlSelection> sqlSelections = new ArrayList<>( selectClause.getSqlSelections() );
			final int specificationsSize = sortSpecifications.size();
			for ( int i = sqlSelections.size() - 1; i != 0; i-- ) {
				final Expression selectionExpression = sqlSelections.get( i ).getExpression();
				for ( int j = 0; j < specificationsSize; j++ ) {
					final Expression expression = resolveAliasedExpression(
							sqlSelections,
							sortSpecifications.get( j ).getSortExpression()
					);
					if ( expression.equals( selectionExpression ) ) {
						sqlSelections.remove( i );
						break;
					}
				}
			}
			final int sqlSelectionsSize = sqlSelections.size();
			if ( sqlSelectionsSize == 0 ) {
				return sortSpecifications;
			}
			else {
				final List<SortSpecification> sortSpecificationsRowNumbering = new ArrayList<>( sqlSelectionsSize + specificationsSize );
				sortSpecificationsRowNumbering.addAll( sortSpecifications );
				for ( int i = 0; i < sqlSelectionsSize; i++ ) {
					sortSpecificationsRowNumbering.add(
							new SortSpecification(
									new SqlSelectionExpression( sqlSelections.get( i ) ),
									null,
									SortOrder.ASCENDING,
									NullPrecedence.NONE
							)
					);
				}
				return sortSpecificationsRowNumbering;
			}
		}
		else if ( queryPart instanceof QueryGroup ) {
			// When the sort specifications come from a query group which uses positional references
			// we have to resolve to the actual selection expressions
			final int specificationsSize = sortSpecifications.size();
			final List<SortSpecification> sortSpecificationsRowNumbering = new ArrayList<>( specificationsSize );
			final List<SqlSelection> sqlSelections = selectClause.getSqlSelections();
			for ( int i = 0; i < specificationsSize; i++ ) {
				final SortSpecification sortSpecification = sortSpecifications.get( i );
				final int position;
				if ( sortSpecification.getSortExpression() instanceof SqlSelectionExpression ) {
					position = ( (SqlSelectionExpression) sortSpecification.getSortExpression() )
							.getSelection()
							.getValuesArrayPosition();
				}
				else {
					assert sortSpecification.getSortExpression() instanceof QueryLiteral;
					final QueryLiteral<?> queryLiteral = (QueryLiteral<?>) sortSpecification.getSortExpression();
					assert queryLiteral.getLiteralValue() instanceof Integer;
					position = (Integer) queryLiteral.getLiteralValue();
				}
				sortSpecificationsRowNumbering.add(
						new SortSpecification(
								new SqlSelectionExpression(
										sqlSelections.get( position )
								),
								null,
								sortSpecification.getSortOrder(),
								sortSpecification.getNullPrecedence()
						)
				);
			}
			return sortSpecificationsRowNumbering;
		}
		else {
			return sortSpecifications;
		}
	}

	@Override
	public void visitSqlSelection(SqlSelection sqlSelection) {
		renderSelectExpression( sqlSelection.getExpression() );
	}

	protected void renderSelectExpression(Expression expression) {
		expression.accept( this );
	}

	protected void renderSelectExpressionWithCastedOrInlinedPlainParameters(Expression expression) {
		// Null literals have to be casted in the select clause
		if ( expression instanceof Literal ) {
			final Literal literal = (Literal) expression;
			if ( literal.getLiteralValue() == null ) {
				renderCasted( literal );
			}
			else {
				renderLiteral( literal, true );
			}
		}
		else if ( expression instanceof NullnessLiteral || expression instanceof JdbcParameter || expression instanceof SqmParameterInterpretation ) {
			renderCasted( expression );
		}
		else if ( expression instanceof CaseSimpleExpression ) {
			visitCaseSimpleExpression( (CaseSimpleExpression) expression, true );
		}
		else if ( expression instanceof CaseSearchedExpression ) {
			visitCaseSearchedExpression( (CaseSearchedExpression) expression, true );
		}
		else {
			expression.accept( this );
		}
	}

	protected void renderCasted(Expression expression) {
		final List<SqlAstNode> arguments = new ArrayList<>( 2 );
		arguments.add( expression );
		arguments.add( new CastTarget( expression.getExpressionType().getJdbcMappings().get( 0 ) ) );
		castFunction().render( this, arguments, this );
	}

	@SuppressWarnings("unchecked")
	protected void renderLiteral(Literal literal, boolean castParameter) {
		assert literal.getExpressionType().getJdbcTypeCount() == 1;

		final JdbcMapping jdbcMapping = literal.getJdbcMapping();
		final JdbcLiteralFormatter literalFormatter = jdbcMapping
				.getJdbcTypeDescriptor()
				.getJdbcLiteralFormatter( jdbcMapping.getJavaTypeDescriptor() );

		// If we encounter a plain literal in the select clause which has no literal formatter, we must render it as parameter
		if ( literalFormatter == null ) {
			parameterBinders.add( literal );

			final LiteralAsParameter<Object> jdbcParameter = new LiteralAsParameter<>( literal );
			if ( castParameter ) {
				final List<SqlAstNode> arguments = new ArrayList<>( 2 );
				arguments.add( jdbcParameter );
				arguments.add( new CastTarget( jdbcMapping ) );
				castFunction().render( this, arguments, this );
			}
			else {
				appendSql( PARAM_MARKER );
			}
		}
		else {
			appendSql(
					literalFormatter.toJdbcLiteral(
							literal.getLiteralValue(),
							dialect,
							getWrapperOptions()
					)
			);
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// FROM clause

	@Override
	public void visitFromClause(FromClause fromClause) {
		if ( fromClause == null || fromClause.getRoots().isEmpty() ) {
			appendSql( getFromDualForSelectOnly() );
		}
		else {
			appendSql( " from " );
			try {
				clauseStack.push( Clause.FROM );
				String separator = NO_SEPARATOR;
				for ( TableGroup root : fromClause.getRoots() ) {
					appendSql( separator );
					renderTableGroup( root );
					separator = COMA_SEPARATOR;
				}
			}
			finally {
				clauseStack.pop();
			}
		}
	}

	@SuppressWarnings("WeakerAccess")
	protected void renderTableGroup(TableGroup tableGroup) {
		// NOTE : commented out blocks render the TableGroup as a CTE

//		if ( tableGroup.getGroupAlias() !=  null ) {
//			sqlAppender.appendSql( OPEN_PARENTHESIS );
//		}

		final LockMode effectiveLockMode = getEffectiveLockMode( tableGroup.getSourceAlias() );
		final boolean usesLockHint = renderTableReference( tableGroup.getPrimaryTableReference(), effectiveLockMode );

		renderTableReferenceJoins( tableGroup );

//		if ( tableGroup.getGroupAlias() !=  null ) {
//			sqlAppender.appendSql( CLOSE_PARENTHESIS );
//			sqlAppender.appendSql( AS_KEYWORD );
//			sqlAppender.appendSql( tableGroup.getGroupAlias() );
//		}

		processTableGroupJoins( tableGroup );
		ModelPartContainer modelPart = tableGroup.getModelPart();
		if ( modelPart instanceof AbstractEntityPersister ) {
			String[] querySpaces = (String[]) ( (AbstractEntityPersister) modelPart ).getQuerySpaces();
			for ( int i = 0; i < querySpaces.length; i++ ) {
				registerAffectedTable( querySpaces[i] );
			}
		}
		if ( !usesLockHint && tableGroup.getSourceAlias() != null && LockMode.READ.lessThan( effectiveLockMode ) ) {
			if ( forUpdate == null ) {
				forUpdate = new ForUpdateClause( effectiveLockMode );
			}
			else {
				forUpdate.setLockMode( effectiveLockMode );
			}
			forUpdate.applyAliases( getDialect().getLockRowIdentifier( effectiveLockMode ), tableGroup );
		}
	}

	protected void renderTableGroup(TableGroup tableGroup, Predicate predicate) {
		// Without reference joins, even a real table group does not need parenthesis
		final boolean realTableGroup = tableGroup.isRealTableGroup()
				&& CollectionHelper.isNotEmpty( tableGroup.getTableReferenceJoins() );
		if ( realTableGroup ) {
			appendSql( '(' );
		}

		final LockMode effectiveLockMode = getEffectiveLockMode( tableGroup.getSourceAlias() );
		final boolean usesLockHint = renderTableReference( tableGroup.getPrimaryTableReference(), effectiveLockMode );

		if ( realTableGroup ) {
			renderTableReferenceJoins( tableGroup );
			appendSql( ')' );
		}

		appendSql( " on " );
		predicate.accept( this );

		if ( !realTableGroup ) {
			renderTableReferenceJoins( tableGroup );
		}
		processTableGroupJoins( tableGroup );

		ModelPartContainer modelPart = tableGroup.getModelPart();
		if ( modelPart instanceof AbstractEntityPersister ) {
			String[] querySpaces = (String[]) ( (AbstractEntityPersister) modelPart ).getQuerySpaces();
			for ( int i = 0; i < querySpaces.length; i++ ) {
				registerAffectedTable( querySpaces[i] );
			}
		}
		if ( !usesLockHint && tableGroup.getSourceAlias() != null && LockMode.READ.lessThan( effectiveLockMode ) ) {
			if ( forUpdate == null ) {
				forUpdate = new ForUpdateClause( effectiveLockMode );
			}
			else {
				forUpdate.setLockMode( effectiveLockMode );
			}
			forUpdate.applyAliases( getDialect().getLockRowIdentifier( effectiveLockMode ), tableGroup );
		}
	}

	@SuppressWarnings("WeakerAccess")
	protected boolean renderTableReference(TableReference tableReference, LockMode lockMode) {
		appendSql( tableReference.getTableExpression() );
		registerAffectedTable( tableReference );
		final Clause currentClause = clauseStack.getCurrent();
		if ( rendersTableReferenceAlias( currentClause ) ) {
			final String identificationVariable = tableReference.getIdentificationVariable();
			if ( identificationVariable != null ) {
				appendSql( getDialect().getTableAliasSeparator() );
				appendSql( identificationVariable );
			}
		}
		return false;
	}

	public static boolean rendersTableReferenceAlias(Clause clause) {
		// todo (6.0) : For now we just skip the alias rendering in the delete and update clauses
		//  We need some dialect support if we want to support joins in delete and update statements
		switch ( clause ) {
			case DELETE:
			case UPDATE:
				return false;
		}
		return true;
	}

	protected void registerAffectedTable(TableReference tableReference) {
		registerAffectedTable( tableReference.getTableExpression() );
	}

	protected void registerAffectedTable(String tableExpression) {
		affectedTableNames.add( tableExpression );
	}

	@SuppressWarnings("WeakerAccess")
	protected void renderTableReferenceJoins(TableGroup tableGroup) {
		final List<TableReferenceJoin> joins = tableGroup.getTableReferenceJoins();
		if ( joins == null || joins.isEmpty() ) {
			return;
		}

		for ( TableReferenceJoin tableJoin : joins ) {
			appendSql( EMPTY_STRING );
			renderJoinType( tableJoin.getJoinType() );

			renderTableReference( tableJoin.getJoinedTableReference(), LockMode.NONE );

			if ( tableJoin.getPredicate() != null && !tableJoin.getPredicate().isEmpty() ) {
				appendSql( " on " );
				tableJoin.getPredicate().accept( this );
			}
		}
	}

	@SuppressWarnings("WeakerAccess")
	protected void processTableGroupJoins(TableGroup source) {
		source.visitTableGroupJoins( this::processTableGroupJoin );
	}

	@SuppressWarnings("WeakerAccess")
	protected void processTableGroupJoin(TableGroupJoin tableGroupJoin) {
		final TableGroup joinedGroup = tableGroupJoin.getJoinedGroup();

		if ( joinedGroup instanceof VirtualTableGroup ) {
			processTableGroupJoins( tableGroupJoin.getJoinedGroup() );
		}
		else if ( !( joinedGroup instanceof LazyTableGroup ) || ( (LazyTableGroup) joinedGroup ).getUnderlyingTableGroup() != null ) {
			appendSql( EMPTY_STRING );
			SqlAstJoinType joinType = tableGroupJoin.getJoinType();
			if ( !joinedGroup.isRealTableGroup() && joinType == SqlAstJoinType.INNER && !joinedGroup.getTableReferenceJoins().isEmpty() ) {
				joinType = SqlAstJoinType.LEFT;
			}
			renderJoinType( joinType );

			if ( tableGroupJoin.getPredicate() != null && !tableGroupJoin.getPredicate().isEmpty() ) {
				renderTableGroup( joinedGroup, tableGroupJoin.getPredicate() );
			}
			else {
				renderTableGroup( joinedGroup );
			}
		}
	}

	protected void renderJoinType(SqlAstJoinType joinType) {
		appendSql( joinType.getText() );
		appendSql( " join " );
	}

	@Override
	public void visitTableGroup(TableGroup tableGroup) {
		// TableGroup and TableGroup handling should be performed as part of `#visitFromClause`...

		// todo (6.0) : what is the correct behavior here?
		appendSql( tableGroup.getPrimaryTableReference().getIdentificationVariable() );
		appendSql( '.' );
		//TODO: pretty sure the typecast to Loadable is quite wrong here

		ModelPartContainer modelPart = tableGroup.getModelPart();
		if ( modelPart instanceof Loadable ) {
			appendSql( ( (Loadable) tableGroup.getModelPart() ).getIdentifierColumnNames()[0] );
		}
		else if ( modelPart instanceof PluralAttributeMapping ) {
			final CollectionPart elementDescriptor = ( (PluralAttributeMapping) modelPart ).getElementDescriptor();
			if ( elementDescriptor instanceof BasicValuedCollectionPart ) {
				String mappedColumnExpression = ( (BasicValuedCollectionPart) elementDescriptor ).getSelectionExpression();
				appendSql( mappedColumnExpression );
			}
			else if ( elementDescriptor instanceof EntityCollectionPart ) {
				final ForeignKeyDescriptor foreignKeyDescriptor = ( (EntityCollectionPart) elementDescriptor ).getForeignKeyDescriptor();
				if ( foreignKeyDescriptor instanceof SimpleForeignKeyDescriptor ) {
					foreignKeyDescriptor.visitTargetSelectables(
							(selectionIndex, selectionMapping) -> appendSql( selectionMapping.getSelectionExpression() )
					);
				}
			}
		}
		else if ( modelPart instanceof ToOneAttributeMapping ) {
			final ForeignKeyDescriptor foreignKeyDescriptor = ( (ToOneAttributeMapping) modelPart ).getForeignKeyDescriptor();
			if ( foreignKeyDescriptor instanceof SimpleForeignKeyDescriptor ) {
				foreignKeyDescriptor.visitTargetSelectables(
						(selectionIndex, selectionMapping) -> appendSql( selectionMapping.getSelectionExpression() )
				);
			}
		}
		else {
			throw new NotYetImplementedFor6Exception( getClass() );
		}
	}

	@Override
	public void visitTableGroupJoin(TableGroupJoin tableGroupJoin) {
		// TableGroup and TableGroupJoin handling should be performed as part of `#visitFromClause`...

		// todo (6.0) : what is the correct behavior here?
		appendSql( tableGroupJoin.getJoinedGroup().getPrimaryTableReference().getIdentificationVariable() );
		appendSql( '.' );
		//TODO: pretty sure the typecast to Loadable is quite wrong here
		appendSql( ( (Loadable) tableGroupJoin.getJoinedGroup().getModelPart() ).getIdentifierColumnNames()[0] );
	}

	@Override
	public void visitTableReference(TableReference tableReference) {
		// nothing to do... handled via TableGroup#render
	}

	@Override
	public void visitTableReferenceJoin(TableReferenceJoin tableReferenceJoin) {
		// nothing to do... handled within TableGroup#render
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Expressions

	@Override
	public void visitColumnReference(ColumnReference columnReference) {
		if ( dmlTargetTableAlias != null && dmlTargetTableAlias.equals( columnReference.getQualifier() ) ) {
			// todo (6.0) : use the Dialect to determine how to handle column references
			//		- specifically should they use the table-alias, the table-expression
			//			or neither for its qualifier

			// for now, use the unqualified form
			appendSql( columnReference.getColumnExpression() );
		}
		else {
			appendSql( columnReference.getExpressionText() );
		}
	}

	@Override
	public void visitExtractUnit(ExtractUnit extractUnit) {
		appendSql( getDialect().translateExtractField( extractUnit.getUnit() ) );
	}

	@Override
	public void visitDurationUnit(DurationUnit unit) {
		appendSql( getDialect().translateDurationField( unit.getUnit() ) );
	}

	@Override
	public void visitFormat(Format format) {
		final String dialectFormat = getDialect().translateDatetimeFormat( format.getFormat() );
		appendSql( "'" );
		appendSql( dialectFormat );
		appendSql( "'" );
	}

	@Override
	public void visitStar(Star star) {
		appendSql( "*" );
	}

	@Override
	public void visitTrimSpecification(TrimSpecification trimSpecification) {
		appendSql( " " );
		appendSql( trimSpecification.getSpecification().toSqlText() );
		appendSql( " " );
	}

	@Override
	public void visitCastTarget(CastTarget castTarget) {
		appendSql(
				getDialect().getCastTypeName(
						(SqlExpressable) castTarget.getExpressionType(),
						castTarget.getLength(),
						castTarget.getPrecision(),
						castTarget.getScale()
				)
		);
	}

	@Override
	public void visitDistinct(Distinct distinct) {
		appendSql( "distinct " );
		distinct.getExpression().accept( this );
	}

	@Override
	public void visitParameter(JdbcParameter jdbcParameter) {
		if ( inlineParameters ) {
			renderExpressionAsLiteral( jdbcParameter, jdbcParameterBindings );
		}
		else {
			appendSql( PARAM_MARKER );

			parameterBinders.add( jdbcParameter.getParameterBinder() );
			jdbcParameters.addParameter( jdbcParameter );
		}
	}

	@Override
	public void render(SqlAstNode sqlAstNode, SqlAstNodeRenderingMode renderingMode) {
		switch ( renderingMode ) {
			case NO_PLAIN_PARAMETER: {
				if ( sqlAstNode instanceof SqmParameterInterpretation ) {
					sqlAstNode = ( (SqmParameterInterpretation) sqlAstNode ).getResolvedExpression();
				}
				if ( sqlAstNode instanceof JdbcParameter ) {
					final JdbcParameter jdbcParameter = (JdbcParameter) sqlAstNode;
					final JdbcMapping jdbcMapping = jdbcParameter.getExpressionType().getJdbcMappings().get( 0 );
					// We try to avoid inlining parameters if possible which can be done by wrapping the parameter
					// in an expression that is semantically unnecessary e.g. numeric + 0 or concat with an empty string
					if ( jdbcMapping.getJdbcTypeDescriptor().isNumber() ) {
						appendSql( '(' );
						sqlAstNode.accept( this );
						appendSql( "+0)" );
						break;
					}
					else if ( jdbcMapping.getJdbcTypeDescriptor().isString() ) {
						final SqmFunctionDescriptor sqmFunctionDescriptor = getSessionFactory().getQueryEngine()
								.getSqmFunctionRegistry()
								.findFunctionDescriptor( "concat" );
						if ( sqmFunctionDescriptor instanceof AbstractSqmSelfRenderingFunctionDescriptor ) {
							final List<SqlAstNode> list = new ArrayList<>( 2 );
							list.add( sqlAstNode );
							list.add( new QueryLiteral<>( "", StringType.INSTANCE ) );
							( (AbstractSqmSelfRenderingFunctionDescriptor) sqmFunctionDescriptor )
									.render( this, list, this );
							break;
						}
					}
					final List<SqlAstNode> arguments = new ArrayList<>( 2 );
					arguments.add( jdbcParameter );
					arguments.add( new CastTarget( jdbcMapping ) );
					castFunction().render( this, arguments, this );
				}
				else {
					sqlAstNode.accept( this );
				}
				break;
			}
			case INLINE_PARAMETERS: {
				boolean inlineParameters = this.inlineParameters;
				this.inlineParameters = true;
				try {
					sqlAstNode.accept( this );
				}
				finally {
					this.inlineParameters = inlineParameters;
				}
				break;
			}
			case DEFAULT:
			default: {
				sqlAstNode.accept( this );
			}
		}
	}

	@Override
	public void visitTuple(SqlTuple tuple) {
		boolean isCurrentWhereClause = clauseStack.getCurrent() == Clause.WHERE;
		if ( isCurrentWhereClause ) {
			appendSql( OPEN_PARENTHESIS );
		}

		renderCommaSeparated( tuple.getExpressions() );

		if ( isCurrentWhereClause ) {
			appendSql( CLOSE_PARENTHESIS );
		}
	}

	protected final void renderCommaSeparated(Iterable<? extends SqlAstNode> expressions) {
		String separator = NO_SEPARATOR;
		for ( SqlAstNode expression : expressions ) {
			appendSql( separator );
			expression.accept( this );
			separator = COMA_SEPARATOR;
		}
	}

	protected final void renderCommaSeparatedSelectExpression(Iterable<? extends SqlAstNode> expressions) {
		String separator = NO_SEPARATOR;
		for ( SqlAstNode expression : expressions ) {
			appendSql( separator );
			if ( expression instanceof Expression ) {
				renderSelectExpression( (Expression) expression );
			}
			else {
				expression.accept( this );
			}
			separator = COMA_SEPARATOR;
		}
	}

	@Override
	public void visitCollate(Collate collate) {
		collate.getExpression().accept( this );
		appendSql( " collate " );
		appendSql( collate.getCollation() );
	}

	@Override
	public void visitSqlSelectionExpression(SqlSelectionExpression expression) {
		final boolean useSelectionPosition = dialect.supportsOrdinalSelectItemReference();

		if ( useSelectionPosition ) {
			appendSql( Integer.toString( expression.getSelection().getJdbcResultSetIndex() ) );
		}
		else {
			expression.getSelection().getExpression().accept( this );
		}
	}


//	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//	// Expression : Function : Non-Standard
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitNonStandardFunctionExpression(NonStandardFunction function) {
//		appendSql( function.getFunctionName() );
//		if ( !function.getArguments().isEmpty() ) {
//			appendSql( OPEN_PARENTHESIS );
//			String separator = NO_SEPARATOR;
//			for ( Expression argumentExpression : function.getArguments() ) {
//				appendSql( separator );
//				argumentExpression.accept( this );
//				separator = COMA_SEPARATOR;
//			}
//			appendSql( CLOSE_PARENTHESIS );
//		}
//	}
//
//
//	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//	// Expression : Function : Standard
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitAbsFunction(AbsFunction function) {
//		appendSql( "abs(" );
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitAvgFunction(AvgFunction function) {
//		appendSql( "avg(" );
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitBitLengthFunction(BitLengthFunction function) {
//		appendSql( "bit_length(" );
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitCastFunction(CastFunction function) {
//		sqlAppender.appendSql( "cast(" );
//		function.getExpressionToCast().accept( this );
//		sqlAppender.appendSql( AS_KEYWORD );
//		sqlAppender.appendSql( determineCastTargetTypeSqlExpression( function ) );
//		sqlAppender.appendSql( CLOSE_PARENTHESIS );
//	}
//
//	private String determineCastTargetTypeSqlExpression(CastFunction castFunction) {
//		if ( castFunction.getExplicitCastTargetTypeSqlExpression() != null ) {
//			return castFunction.getExplicitCastTargetTypeSqlExpression();
//		}
//
//		final SqlExpressableType castResultType = castFunction.getCastResultType();
//
//		if ( castResultType == null ) {
//			throw new SqlTreeException(
//					"CastFunction did not define an explicit cast target SQL expression and its return type was null"
//			);
//		}
//
//		final BasicJavaDescriptor javaTypeDescriptor = castResultType.getJavaTypeDescriptor();
//		return getJdbcServices()
//				.getDialect()
//				.getCastTypeName( javaTypeDescriptor.getJdbcRecommendedSqlType( this ).getJdbcTypeCode() );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitConcatFunction(ConcatFunction function) {
//		appendSql( "concat(" );
//
//		boolean firstPass = true;
//		for ( Expression expression : function.getExpressions() ) {
//			if ( ! firstPass ) {
//				appendSql( COMA_SEPARATOR );
//			}
//			expression.accept( this );
//			firstPass = false;
//		}
//
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitSubstrFunction(SubstrFunction function) {
//		appendSql( "substr(" );
//
//		boolean firstPass = true;
//		for ( Expression expression : function.getExpressions() ) {
//			if ( ! firstPass ) {
//				appendSql( COMA_SEPARATOR );
//			}
//			expression.accept( this );
//			firstPass = false;
//		}
//
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitCountFunction(CountFunction function) {
//		appendSql( "count(" );
//		if ( function.isDistinct() ) {
//			appendSql( DISTINCT_KEYWORD );
//		}
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	public void visitCountStarFunction(CountStarFunction function) {
//		appendSql( "count(" );
//		if ( function.isDistinct() ) {
//			appendSql( DISTINCT_KEYWORD );
//		}
//		appendSql( "*)" );
//	}
//
//	@Override
//	public void visitCurrentDateFunction(CurrentDateFunction function) {
//		appendSql( "current_date" );
//	}
//
//	@Override
//	public void visitCurrentTimeFunction(CurrentTimeFunction function) {
//		appendSql( "current_time" );
//	}
//
//	@Override
//	public void visitCurrentTimestampFunction(CurrentTimestampFunction function) {
//		appendSql( "current_timestamp" );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitExtractFunction(ExtractFunction extractFunction) {
//		appendSql( "extract(" );
//		extractFunction.getUnitToExtract().accept( this );
//		appendSql( FROM_KEYWORD );
//		extractFunction.getExtractionSource().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitLengthFunction(LengthFunction function) {
//		sqlAppender.appendSql( "length(" );
//		function.getArgument().accept( this );
//		sqlAppender.appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitLocateFunction(LocateFunction function) {
//		appendSql( "locate(" );
//		function.getPatternString().accept( this );
//		appendSql( COMA_SEPARATOR );
//		function.getStringToSearch().accept( this );
//		if ( function.getStartPosition() != null ) {
//			appendSql( COMA_SEPARATOR );
//			function.getStartPosition().accept( this );
//		}
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitLowerFunction(LowerFunction function) {
//		appendSql( "lower(" );
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitMaxFunction(MaxFunction function) {
//		appendSql( "max(" );
//		if ( function.isDistinct() ) {
//			appendSql( DISTINCT_KEYWORD );
//		}
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitMinFunction(MinFunction function) {
//		appendSql( "min(" );
//		if ( function.isDistinct() ) {
//			appendSql( DISTINCT_KEYWORD );
//		}
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitModFunction(ModFunction function) {
//		sqlAppender.appendSql( "mod(" );
//		function.getDividend().accept( this );
//		sqlAppender.appendSql( COMA_SEPARATOR );
//		function.getDivisor().accept( this );
//		sqlAppender.appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitSqrtFunction(SqrtFunction function) {
//		appendSql( "sqrt(" );
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitSumFunction(SumFunction function) {
//		appendSql( "sum(" );
//		if ( function.isDistinct() ) {
//			appendSql( DISTINCT_KEYWORD );
//		}
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitTrimFunction(TrimFunction function) {
//		sqlAppender.appendSql( "trim(" );
//		sqlAppender.appendSql( function.getSpecification().toSqlText() );
//		sqlAppender.appendSql( EMPTY_STRING_SEPARATOR );
//		function.getTrimCharacter().accept( this );
//		sqlAppender.appendSql( FROM_KEYWORD );
//		function.getSource().accept( this );
//		sqlAppender.appendSql( CLOSE_PARENTHESIS );
//
//	}
//
//	@Override
//	@SuppressWarnings("unchecked")
//	public void visitUpperFunction(UpperFunction function) {
//		appendSql( "upper(" );
//		function.getArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	public void visitCoalesceFunction(CoalesceFunction coalesceExpression) {
//		appendSql( "coalesce(" );
//		String separator = NO_SEPARATOR;
//		for ( Expression expression : coalesceExpression.getValues() ) {
//			appendSql( separator );
//			expression.accept( this );
//			separator = COMA_SEPARATOR;
//		}
//
//		appendSql( CLOSE_PARENTHESIS );
//	}
//
//	@Override
//	public void visitNullifFunction(NullifFunction function) {
//		appendSql( "nullif(" );
//		function.getFirstArgument().accept( this );
//		appendSql( COMA_SEPARATOR );
//		function.getSecondArgument().accept( this );
//		appendSql( CLOSE_PARENTHESIS );
//	}


	@Override
	public void visitEntityTypeLiteral(EntityTypeLiteral expression) {
		final EntityPersister entityTypeDescriptor = expression.getEntityTypeDescriptor();
		appendSql( (( Queryable ) entityTypeDescriptor).getDiscriminatorSQLValue() );
	}

	@Override
	public void visitBinaryArithmeticExpression(BinaryArithmeticExpression arithmeticExpression) {
		appendSql( "(" );
		arithmeticExpression.getLeftHandOperand().accept( this );
		appendSql( arithmeticExpression.getOperator().getOperatorSqlTextString() );
		arithmeticExpression.getRightHandOperand().accept( this );
		appendSql( ")" );
	}

	@Override
	public void visitDuration(Duration duration) {
		duration.getMagnitude().accept( this );
		appendSql(
				duration.getUnit().conversionFactor( NANOSECOND, getDialect() )
		);
	}

	@Override
	public void visitConversion(Conversion conversion) {
		conversion.getDuration().getMagnitude().accept( this );
		appendSql(
				conversion.getDuration().getUnit().conversionFactor(
						conversion.getUnit(), getDialect()
				)
		);
	}

	@Override
	public final void visitCaseSearchedExpression(CaseSearchedExpression caseSearchedExpression) {
		visitCaseSearchedExpression( caseSearchedExpression, false );
	}

	protected void visitCaseSearchedExpression(CaseSearchedExpression caseSearchedExpression, boolean inSelect) {
		if ( inSelect ) {
			visitAnsiCaseSearchedExpressionInSelect( caseSearchedExpression );
		}
		else {
			visitAnsiCaseSearchedExpression( caseSearchedExpression );
		}
	}

	protected void visitAnsiCaseSearchedExpression(CaseSearchedExpression caseSearchedExpression){
		appendSql( "case" );

		for ( CaseSearchedExpression.WhenFragment whenFragment : caseSearchedExpression.getWhenFragments() ) {
			appendSql( " when " );
			whenFragment.getPredicate().accept( this );
			appendSql( " then " );
			whenFragment.getResult().accept( this );
		}

		Expression otherwise = caseSearchedExpression.getOtherwise();
		if ( otherwise != null ) {
			appendSql( " else " );
			otherwise.accept( this );
		}

		appendSql( " end" );
	}

	protected void visitAnsiCaseSearchedExpressionInSelect(CaseSearchedExpression caseSearchedExpression) {
		appendSql( "case" );

		for ( CaseSearchedExpression.WhenFragment whenFragment : caseSearchedExpression.getWhenFragments() ) {
			appendSql( " when " );
			whenFragment.getPredicate().accept( this );
			appendSql( " then " );
			renderSelectExpression( whenFragment.getResult() );
		}

		Expression otherwise = caseSearchedExpression.getOtherwise();
		if ( otherwise != null ) {
			appendSql( " else " );
			renderSelectExpression( otherwise );
		}

		appendSql( " end" );
	}

	protected void visitDecodeCaseSearchedExpression(CaseSearchedExpression caseSearchedExpression) {
		appendSql( "decode( " );

		List<CaseSearchedExpression.WhenFragment> whenFragments = caseSearchedExpression.getWhenFragments();
		int caseNumber = whenFragments.size();
		CaseSearchedExpression.WhenFragment firstWhenFragment = null;
		for ( int i = 0; i < caseNumber; i++ ) {
			final CaseSearchedExpression.WhenFragment whenFragment = whenFragments.get( i );
			Predicate predicate = whenFragment.getPredicate();
			if ( i != 0 ) {
				appendSql( ", " );
				getLeftHandExpression( predicate ).accept( this );
				appendSql( ", " );
				whenFragment.getResult().accept( this );
			}
			else {
				getLeftHandExpression( predicate ).accept( this );
				firstWhenFragment = whenFragment;
			}
		}
		appendSql( ", " );
		firstWhenFragment.getResult().accept( this );

		Expression otherwise = caseSearchedExpression.getOtherwise();
		if ( otherwise != null ) {
			appendSql( ", " );
			otherwise.accept( this );
		}

		appendSql( ')' );
	}

	protected Expression getLeftHandExpression(Predicate predicate) {
		if ( predicate instanceof NullnessPredicate ) {
			return ( (NullnessPredicate) predicate ).getExpression();
		}
		assert predicate instanceof ComparisonPredicate;
		return ( (ComparisonPredicate) predicate ).getLeftHandExpression();
	}

	@Override
	public final void visitCaseSimpleExpression(CaseSimpleExpression caseSimpleExpression) {
		visitCaseSimpleExpression( caseSimpleExpression, false );
	}

	protected void visitCaseSimpleExpression(CaseSimpleExpression caseSimpleExpression, boolean inSelect) {
		if ( inSelect ) {
			visitAnsiCaseSimpleExpressionInSelect( caseSimpleExpression );
		}
		else {
			visitAnsiCaseSimpleExpression( caseSimpleExpression );
		}
	}

	protected void visitAnsiCaseSimpleExpression(CaseSimpleExpression caseSimpleExpression) {
		appendSql( "case " );
		caseSimpleExpression.getFixture().accept( this );
		for ( CaseSimpleExpression.WhenFragment whenFragment : caseSimpleExpression.getWhenFragments() ) {
			appendSql( " when " );
			whenFragment.getCheckValue().accept( this );
			appendSql( " then " );
			whenFragment.getResult().accept( this );
		}
		final Expression otherwise = caseSimpleExpression.getOtherwise();
		if ( otherwise != null ) {
			appendSql( " else " );
			otherwise.accept( this );
		}
		appendSql( " end" );
	}

	protected void visitAnsiCaseSimpleExpressionInSelect(CaseSimpleExpression caseSimpleExpression) {
		appendSql( "case " );
		caseSimpleExpression.getFixture().accept( this );
		for ( CaseSimpleExpression.WhenFragment whenFragment : caseSimpleExpression.getWhenFragments() ) {
			appendSql( " when " );
			whenFragment.getCheckValue().accept( this );
			appendSql( " then " );
			renderSelectExpression( whenFragment.getResult() );
		}
		final Expression otherwise = caseSimpleExpression.getOtherwise();
		if ( otherwise != null ) {
			appendSql( " else " );
			renderSelectExpression( otherwise );
		}
		appendSql( " end" );
	}

	@Override
	public void visitAny(Any any) {
		appendSql( "some " );
		any.getSubquery().accept( this );
	}

	@Override
	public void visitEvery(Every every) {
		appendSql( "all " );
		every.getSubquery().accept( this );
	}

	@Override
	public void visitSummarization(Summarization every) {
		// nothing to do... handled within #renderGroupByItem
	}

	@Override
	public void visitJdbcLiteral(JdbcLiteral jdbcLiteral) {
		visitLiteral( jdbcLiteral );
	}

	@Override
	public void visitQueryLiteral(QueryLiteral queryLiteral) {
		visitLiteral( queryLiteral );
	}

	@Override
	public void visitNullnessLiteral(NullnessLiteral nullnessLiteral) {
		// todo (6.0) : account for composite nulls?
		appendSql( "null" );
	}

	private void visitLiteral(Literal literal) {
		if ( literal.getLiteralValue() == null ) {
			// todo : not sure we allow this "higher up"
			appendSql( SqlAppender.NULL_KEYWORD );
		}
		else {
			renderLiteral( literal, false );
		}
	}

	protected void renderAsLiteral(JdbcParameter jdbcParameter, Object literalValue) {
		if ( literalValue == null ) {
			appendSql( SqlAppender.NULL_KEYWORD );
		}
		else {
			assert jdbcParameter.getExpressionType().getJdbcTypeCount() == 1;
			final JdbcMapping jdbcMapping = jdbcParameter.getExpressionType().getJdbcMappings().get( 0 );
			final JdbcLiteralFormatter literalFormatter = jdbcMapping.getJdbcTypeDescriptor().getJdbcLiteralFormatter( jdbcMapping.getJavaTypeDescriptor() );
			if ( literalFormatter == null ) {
				throw new IllegalArgumentException( "Can't render parameter as literal, no literal formatter found" );
			}
			else {
				appendSql(
						literalFormatter.toJdbcLiteral(
								literalValue,
								dialect,
								getWrapperOptions()
						)
				);
			}
		}
	}

	@Override
	public void visitUnaryOperationExpression(UnaryOperation unaryOperationExpression) {
		if ( unaryOperationExpression.getOperator() == UnaryArithmeticOperator.UNARY_PLUS ) {
			appendSql( UnaryArithmeticOperator.UNARY_PLUS.getOperatorChar() );
		}
		else {
			appendSql( UnaryArithmeticOperator.UNARY_MINUS.getOperatorChar() );
		}

		unaryOperationExpression.getOperand().accept( this );
	}

	@Override
	public void visitSelfRenderingPredicate(SelfRenderingPredicate selfRenderingPredicate) {
		// todo (6.0) render boolean expression as comparison predicate if necessary
		selfRenderingPredicate.getSelfRenderingExpression().renderToSql( this, this, getSessionFactory() );
	}

	@Override
	public void visitSelfRenderingExpression(SelfRenderingExpression expression) {
		expression.renderToSql( this, this, getSessionFactory() );
	}

//	@Override
//	public void visitPluralAttribute(PluralAttributeReference pluralAttributeReference) {
//		// todo (6.0) - is this valid in the general sense?  Or specific to things like order-by rendering?
//		//		long story short... what should we do here?
//	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Predicates

	@Override
	public void visitBetweenPredicate(BetweenPredicate betweenPredicate) {
		betweenPredicate.getExpression().accept( this );
		if ( betweenPredicate.isNegated() ) {
			appendSql( " not" );
		}
		appendSql( " between " );
		betweenPredicate.getLowerBound().accept( this );
		appendSql( " and " );
		betweenPredicate.getUpperBound().accept( this );
	}

	@Override
	public void visitFilterPredicate(FilterPredicate filterPredicate) {
		assert StringHelper.isNotEmpty( filterPredicate.getFilterFragment() );
		appendSql( filterPredicate.getFilterFragment() );
		for ( FilterJdbcParameter filterJdbcParameter : filterPredicate.getFilterJdbcParameters() ) {
			parameterBinders.add( filterJdbcParameter.getBinder() );
			jdbcParameters.addParameter( filterJdbcParameter.getParameter() );
			filterJdbcParameters.add( filterJdbcParameter );
		}
	}

	@Override
	public void visitGroupedPredicate(GroupedPredicate groupedPredicate) {
		if ( groupedPredicate.isEmpty() ) {
			return;
		}

		appendSql( OPEN_PARENTHESIS );
		groupedPredicate.getSubPredicate().accept( this );
		appendSql( CLOSE_PARENTHESIS );
	}

	@Override
	public void visitInListPredicate(InListPredicate inListPredicate) {
		final List<Expression> listExpressions = inListPredicate.getListExpressions();
		if ( listExpressions.isEmpty() ) {
			appendSql( "false" );
			return;
		}
		final SqlTuple lhsTuple;
		if ( ( lhsTuple = getTuple( inListPredicate.getTestExpression() ) ) != null ) {
			if ( lhsTuple.getExpressions().size() == 1 ) {
				// Special case for tuples with arity 1 as any DBMS supports scalar IN predicates
				lhsTuple.getExpressions().get( 0 ).accept( this );
				if ( inListPredicate.isNegated() ) {
					appendSql( " not" );
				}
				appendSql( " in (" );
				String separator = NO_SEPARATOR;
				for ( Expression expression : listExpressions ) {
					appendSql( separator );
					getTuple( expression ).getExpressions().get( 0 ).accept( this );
					separator = COMA_SEPARATOR;
				}
				appendSql( CLOSE_PARENTHESIS );
			}
			else if ( !supportsRowValueConstructorSyntaxInInList() ) {
				final ComparisonOperator comparisonOperator = inListPredicate.isNegated() ?
						ComparisonOperator.NOT_EQUAL :
						ComparisonOperator.EQUAL;
				// Some DBs like Oracle support tuples only for the IN subquery predicate
				if ( supportsRowValueConstructorSyntaxInInSubQuery() && dialect.supportsUnionAll() ) {
					inListPredicate.getTestExpression().accept( this );
					if ( inListPredicate.isNegated() ) {
						appendSql( " not" );
					}
					appendSql( " in (" );
					String separator = NO_SEPARATOR;
					for ( Expression expression : listExpressions ) {
						appendSql( separator );
						renderExpressionsAsSubquery(
								getTuple( expression ).getExpressions()
						);
						separator = " union all ";
					}
					appendSql( CLOSE_PARENTHESIS );
				}
				else {
					String separator = NO_SEPARATOR;
					for ( Expression expression : listExpressions ) {
						appendSql( separator );
						emulateTupleComparison(
								lhsTuple.getExpressions(),
								getTuple( expression ).getExpressions(),
								comparisonOperator,
								true
						);
						separator = " or ";
					}
				}
			}
			else {
				inListPredicate.getTestExpression().accept( this );
				if ( inListPredicate.isNegated() ) {
					appendSql( " not" );
				}
				appendSql( " in (" );
				renderCommaSeparated( listExpressions );
				appendSql( CLOSE_PARENTHESIS );
			}
		}
		else {
			inListPredicate.getTestExpression().accept( this );
			if ( inListPredicate.isNegated() ) {
				appendSql( " not" );
			}
			appendSql( " in (" );
			renderCommaSeparated( listExpressions );
			appendSql( CLOSE_PARENTHESIS );
		}
	}

	protected final SqlTuple getTuple(Expression expression) {
		if ( expression instanceof SqlTupleContainer ) {
			return ( (SqlTupleContainer) expression ).getSqlTuple();
		}

		return null;
	}

	@Override
	public void visitInSubQueryPredicate(InSubQueryPredicate inSubQueryPredicate) {
		final SqlTuple lhsTuple;
		if ( ( lhsTuple = getTuple( inSubQueryPredicate.getTestExpression() ) ) != null ) {
			if ( lhsTuple.getExpressions().size() == 1 ) {
				// Special case for tuples with arity 1 as any DBMS supports scalar IN predicates
				lhsTuple.getExpressions().get( 0 ).accept( this );
				if ( inSubQueryPredicate.isNegated() ) {
					appendSql( " not" );
				}
				appendSql( " in " );
				inSubQueryPredicate.getSubQuery().accept( this );
			}
			else if ( !supportsRowValueConstructorSyntaxInInSubQuery() ) {
				emulateSubQueryRelationalRestrictionPredicate(
						inSubQueryPredicate,
						inSubQueryPredicate.isNegated(),
						inSubQueryPredicate.getSubQuery(),
						lhsTuple,
						this::renderSelectTupleComparison,
						ComparisonOperator.EQUAL
				);
			}
			else {
				inSubQueryPredicate.getTestExpression().accept( this );
				if ( inSubQueryPredicate.isNegated() ) {
					appendSql( " not" );
				}
				appendSql( " in " );
				inSubQueryPredicate.getSubQuery().accept( this );
			}
		}
		else {
			inSubQueryPredicate.getTestExpression().accept( this );
			if ( inSubQueryPredicate.isNegated() ) {
				appendSql( " not" );
			}
			appendSql( " in " );
			inSubQueryPredicate.getSubQuery().accept( this );
		}
	}

	protected <X extends Expression> void emulateSubQueryRelationalRestrictionPredicate(
			Predicate predicate,
			boolean negated,
			QueryPart queryPart,
			X lhsTuple,
			SubQueryRelationalRestrictionEmulationRenderer<X> renderer,
			ComparisonOperator tupleComparisonOperator) {
		final QuerySpec subQuery;
		if ( queryPart instanceof QuerySpec && queryPart.getFetchClauseExpression() == null && queryPart.getOffsetClauseExpression() == null ) {
			subQuery = (QuerySpec) queryPart;
			// We can only emulate the tuple sub query predicate as exists predicate when there are no limit/offsets
			if ( negated ) {
				appendSql( "not " );
			}

			final QueryPart queryPartForRowNumbering = this.queryPartForRowNumbering;
			final int queryPartForRowNumberingClauseDepth = this.queryPartForRowNumberingClauseDepth;
			final boolean needsSelectAliases = this.needsSelectAliases;
			try {
				this.queryPartForRowNumbering = null;
				this.queryPartForRowNumberingClauseDepth = -1;
				this.needsSelectAliases = false;
				queryPartStack.push( subQuery );
				appendSql( "exists (select 1" );
				visitFromClause( subQuery.getFromClause() );

				if ( !subQuery.getGroupByClauseExpressions()
						.isEmpty() || subQuery.getHavingClauseRestrictions() != null ) {
					// If we have a group by or having clause, we have to move the tuple comparison emulation to the HAVING clause
					visitWhereClause( subQuery );
					visitGroupByClause( subQuery, false );

					appendSql( " having " );
					clauseStack.push( Clause.HAVING );
					try {
						renderer.renderComparison(
								subQuery.getSelectClause().getSqlSelections(),
								lhsTuple,
								tupleComparisonOperator
						);
						final Predicate havingClauseRestrictions = subQuery.getHavingClauseRestrictions();
						if ( havingClauseRestrictions != null ) {
							appendSql( " and (" );
							havingClauseRestrictions.accept( this );
							appendSql( ')' );
						}
					}
					finally {
						clauseStack.pop();
					}
				}
				else {
					// If we have no group by or having clause, we can move the tuple comparison emulation to the WHERE clause
					appendSql( " where " );
					clauseStack.push( Clause.WHERE );
					try {
						renderer.renderComparison(
								subQuery.getSelectClause().getSqlSelections(),
								lhsTuple,
								tupleComparisonOperator
						);
						final Predicate whereClauseRestrictions = subQuery.getWhereClauseRestrictions();
						if ( whereClauseRestrictions != null ) {
							appendSql( " and (" );
							whereClauseRestrictions.accept( this );
							appendSql( ')' );
						}
					}
					finally {
						clauseStack.pop();
					}
				}

				appendSql( ')' );
			}
			finally {
				queryPartStack.pop();
				this.queryPartForRowNumbering = queryPartForRowNumbering;
				this.queryPartForRowNumberingClauseDepth = queryPartForRowNumberingClauseDepth;
				this.needsSelectAliases = needsSelectAliases;
			}
		}
		else {
			// TODO: We could use nested queries and use row numbers to emulate this
			throw new IllegalArgumentException( "Can't emulate in predicate with tuples and limit/offset or set operations: " + predicate );
		}
	}

	protected static interface SubQueryRelationalRestrictionEmulationRenderer<X extends Expression> {
		void renderComparison(final List<SqlSelection> lhsExpressions, X rhsExpression, ComparisonOperator operator);
	}

	/**
	 * An optimized emulation for relational tuple sub-query comparisons.
	 * The idea of this method is to use limit 1 to select the max or min tuple and only compare against that.
	 */
	protected void emulateQuantifiedTupleSubQueryPredicate(
			Predicate predicate,
			QueryPart queryPart,
			SqlTuple lhsTuple,
			ComparisonOperator tupleComparisonOperator) {
		final QuerySpec subQuery;
		if ( queryPart instanceof QuerySpec && queryPart.getFetchClauseExpression() == null && queryPart.getOffsetClauseExpression() == null ) {
			subQuery = (QuerySpec) queryPart;
			// We can only emulate the tuple sub query predicate comparing against the top element when there are no limit/offsets
			lhsTuple.accept( this );
			appendSql( " " );
			appendSql( tupleComparisonOperator.sqlText() );
			appendSql( " " );

			final QueryPart queryPartForRowNumbering = this.queryPartForRowNumbering;
			final int queryPartForRowNumberingClauseDepth = this.queryPartForRowNumberingClauseDepth;
			final boolean needsSelectAliases = this.needsSelectAliases;
			try {
				this.queryPartForRowNumbering = null;
				this.queryPartForRowNumberingClauseDepth = -1;
				this.needsSelectAliases = false;
				queryPartStack.push( subQuery );
				appendSql( '(' );
				visitSelectClause( subQuery.getSelectClause() );
				visitFromClause( subQuery.getFromClause() );
				visitWhereClause( subQuery );
				visitGroupByClause( subQuery, dialect.supportsSelectAliasInGroupByClause() );
				visitHavingClause( subQuery );

				appendSql( " order by " );
				final List<SqlSelection> sqlSelections = subQuery.getSelectClause().getSqlSelections();
				final String order;
				if ( tupleComparisonOperator == ComparisonOperator.LESS_THAN || tupleComparisonOperator == ComparisonOperator.LESS_THAN_OR_EQUAL ) {
					// Default order is asc so we don't need to specify the order explicitly
					order = "";
				}
				else {
					order = " desc";
				}
				appendSql( "1" );
				appendSql( order );
				for ( int i = 1; i < sqlSelections.size(); i++ ) {
					appendSql( COMA_SEPARATOR );
					appendSql( Integer.toString( i + 1 ) );
					appendSql( order );
				}
				renderFetch( ONE_LITERAL, null, FetchClauseType.ROWS_ONLY );
				appendSql( ')' );
			}
			finally {
				queryPartStack.pop();
				this.queryPartForRowNumbering = queryPartForRowNumbering;
				this.queryPartForRowNumberingClauseDepth = queryPartForRowNumberingClauseDepth;
				this.needsSelectAliases = needsSelectAliases;
			}
		}
		else {
			// TODO: We could use nested queries and use row numbers to emulate this
			throw new IllegalArgumentException( "Can't emulate in predicate with tuples and limit/offset or set operations: " + predicate );
		}
	}

	@Override
	public void visitExistsPredicate(ExistsPredicate existsPredicate) {
		appendSql( "exists " );
		existsPredicate.getExpression().accept( this );
	}

	@Override
	public void visitJunction(Junction junction) {
		if ( junction.isEmpty() ) {
			return;
		}

		final Junction.Nature nature = junction.getNature();
		final String separator = nature == Junction.Nature.CONJUNCTION
				? " and "
				: " or ";
		final List<Predicate> predicates = junction.getPredicates();
		visitJunctionPredicate( nature, predicates.get( 0 ) );
		for ( int i = 1; i < predicates.size(); i++ ) {
			appendSql( separator );
			visitJunctionPredicate( nature, predicates.get( i ) );
		}
	}

	private void visitJunctionPredicate(Junction.Nature nature, Predicate p) {
		if ( p instanceof Junction && nature != ( (Junction) p ).getNature() ) {
			appendSql( '(' );
			p.accept( this );
			appendSql( ')' );
		}
		else {
			p.accept( this );
		}
	}

	@Override
	public void visitLikePredicate(LikePredicate likePredicate) {
		likePredicate.getMatchExpression().accept( this );
		if ( likePredicate.isNegated() ) {
			appendSql( " not" );
		}
		appendSql( " like " );
		likePredicate.getPattern().accept( this );
		if ( likePredicate.getEscapeCharacter() != null ) {
			appendSql( " escape " );
			likePredicate.getEscapeCharacter().accept( this );
		}
	}

	@Override
	public void visitNegatedPredicate(NegatedPredicate negatedPredicate) {
		if ( negatedPredicate.isEmpty() ) {
			return;
		}

		appendSql( "not (" );
		negatedPredicate.getPredicate().accept( this );
		appendSql( ")" );
	}

	@Override
	public void visitNullnessPredicate(NullnessPredicate nullnessPredicate) {
		final Expression expression = nullnessPredicate.getExpression();
		final String predicateValue;
		if ( nullnessPredicate.isNegated() ) {
			predicateValue = " is not null";
		}
		else {
			predicateValue = " is null";
		}
		final SqlTuple tuple;
		if ( ( tuple = getTuple( expression ) ) != null ) {
			String separator = NO_SEPARATOR;
			for ( Expression exp : tuple.getExpressions() ) {
				appendSql( separator );
				exp.accept( this );
				appendSql( predicateValue );
				separator = " and ";
			}
		}
		else {
			expression.accept( this );
			appendSql( predicateValue );
		}
	}

	@Override
	public void visitRelationalPredicate(ComparisonPredicate comparisonPredicate) {
		// todo (6.0) : do we want to allow multi-valued parameters in a relational predicate?
		//		yes means we'd have to support dynamically converting this predicate into
		//		an IN predicate or an OR predicate
		//
		//		NOTE: JPA does not define support for multi-valued parameters here.
		//
		// If we decide to support that ^^  we should validate that *both* sides of the
		//		predicate are multi-valued parameters.  because...
		//		well... its stupid :)

		final SqlTuple lhsTuple;
		final SqlTuple rhsTuple;
		if ( ( lhsTuple = getTuple( comparisonPredicate.getLeftHandExpression() ) ) != null ) {
			final Expression rhsExpression = comparisonPredicate.getRightHandExpression();
			final boolean all;
			final QueryPart subquery;

			// Handle emulation of quantified comparison
			if ( rhsExpression instanceof QueryPart ) {
				subquery = (QueryPart) rhsExpression;
				all = true;
			}
			else if ( rhsExpression instanceof Every ) {
				subquery = ( (Every) rhsExpression ).getSubquery();
				all = true;
			}
			else if ( rhsExpression instanceof Any ) {
				subquery = ( (Any) rhsExpression ).getSubquery();
				all = false;
			}
			else {
				subquery = null;
				all = false;
			}

			final ComparisonOperator operator = comparisonPredicate.getOperator();
			if ( lhsTuple.getExpressions().size() == 1 ) {
				// Special case for tuples with arity 1 as any DBMS supports scalar IN predicates
				if ( subquery == null ) {
					renderComparison(
							lhsTuple.getExpressions().get( 0 ),
							operator,
							getTuple( comparisonPredicate.getRightHandExpression() ).getExpressions().get( 0 )
					);
				}
				else {
					renderComparison( lhsTuple.getExpressions().get( 0 ), operator, rhsExpression );
				}
			}
			else if ( subquery != null && !supportsRowValueConstructorSyntaxInQuantifiedPredicates() ) {
				// For quantified relational comparisons, we can do an optimized emulation
				if ( supportsRowValueConstructorSyntax() && all ) {
					switch ( operator ) {
						case LESS_THAN:
						case LESS_THAN_OR_EQUAL:
						case GREATER_THAN:
						case GREATER_THAN_OR_EQUAL: {
							emulateQuantifiedTupleSubQueryPredicate(
									comparisonPredicate,
									subquery,
									lhsTuple,
									operator
							);
							return;
						}
					}
				}
				emulateSubQueryRelationalRestrictionPredicate(
						comparisonPredicate,
						all,
						subquery,
						lhsTuple,
						this::renderSelectTupleComparison,
						all ? operator.negated() : operator
				);
			}
			else if ( !supportsRowValueConstructorSyntax() ) {
				rhsTuple = getTuple( rhsExpression );
				assert rhsTuple != null;
				// Some DBs like Oracle support tuples only for the IN subquery predicate
				if ( ( operator == ComparisonOperator.EQUAL || operator == ComparisonOperator.NOT_EQUAL ) && supportsRowValueConstructorSyntaxInInSubQuery() ) {
					comparisonPredicate.getLeftHandExpression().accept( this );
					if ( operator == ComparisonOperator.NOT_EQUAL ) {
						appendSql( " not" );
					}
					appendSql( " in (" );
					renderExpressionsAsSubquery( rhsTuple.getExpressions() );
					appendSql( CLOSE_PARENTHESIS );
				}
				else {
					emulateTupleComparison(
							lhsTuple.getExpressions(),
							rhsTuple.getExpressions(),
							operator,
							true
					);
				}
			}
			else {
				renderComparison( comparisonPredicate.getLeftHandExpression(), operator, rhsExpression );
			}
		}
		else if ( ( rhsTuple = getTuple( comparisonPredicate.getRightHandExpression() ) ) != null ) {
			final Expression lhsExpression = comparisonPredicate.getLeftHandExpression();

			if ( lhsExpression instanceof QueryGroup ) {
				final QueryGroup subquery = (QueryGroup) lhsExpression;

				if ( rhsTuple.getExpressions().size() == 1 ) {
					// Special case for tuples with arity 1 as any DBMS supports scalar IN predicates
					renderComparison(
							lhsExpression,
							comparisonPredicate.getOperator(),
							rhsTuple.getExpressions().get( 0 )
					);
				}
				else if ( supportsRowValueConstructorSyntax() ) {
					renderComparison(
							lhsExpression,
							comparisonPredicate.getOperator(),
							comparisonPredicate.getRightHandExpression()
					);
				}
				else {
					emulateSubQueryRelationalRestrictionPredicate(
							comparisonPredicate,
							false,
							subquery,
							rhsTuple,
							this::renderSelectTupleComparison,
							// Since we switch the order of operands, we have to invert the operator
							comparisonPredicate.getOperator().invert()
					);
				}
			}
			else {
				throw new IllegalStateException(
						"Unsupported tuple comparison combination. LHS is neither a tuple nor a tuple subquery but RHS is a tuple: " + comparisonPredicate );
			}
		}
		else {
			renderComparison(
					comparisonPredicate.getLeftHandExpression(),
					comparisonPredicate.getOperator(),
					comparisonPredicate.getRightHandExpression()
			);
		}
	}

	/**
	 * Is this dialect known to support quantified predicates.
	 * <p/>
	 * Basically, does it support syntax like
	 * "... where FIRST_NAME > ALL (select ...) ...".
	 *
	 * @return True if this SQL dialect is known to support quantified predicates; false otherwise.
	 */
	protected boolean supportsQuantifiedPredicates() {
		return true;
	}

	/**
	 * Is this dialect known to support what ANSI-SQL terms "row value
	 * constructor" syntax; sometimes called tuple syntax.
	 * <p/>
	 * Basically, does it support syntax like
	 * "... where (FIRST_NAME, LAST_NAME) = ('Steve', 'Ebersole') ...".
	 *
	 * @return True if this SQL dialect is known to support "row value
	 * constructor" syntax; false otherwise.
	 */
	protected boolean supportsRowValueConstructorSyntax() {
		return true;
	}

	/**
	 * Is this dialect known to support  what ANSI-SQL terms "row value constructor" syntax,
	 * sometimes called tuple syntax, in the SET clause;
	 * <p/>
	 * Basically, does it support syntax like
	 * "... SET (FIRST_NAME, LAST_NAME) = ('Steve', 'Ebersole') ...".
	 *
	 * @return True if this SQL dialect is known to support "row value constructor" syntax in the SET clause; false otherwise.
	 */
	protected boolean supportsRowValueConstructorSyntaxInSet() {
		return supportsRowValueConstructorSyntax();
	}

	/**
	 * Is this dialect known to support what ANSI-SQL terms "row value
	 * constructor" syntax; sometimes called tuple syntax with quantified predicates.
	 * <p/>
	 * Basically, does it support syntax like
	 * "... where (FIRST_NAME, LAST_NAME) = ALL (select ...) ...".
	 *
	 * @return True if this SQL dialect is known to support "row value
	 * constructor" syntax with quantified predicates; false otherwise.
	 */
	protected boolean supportsRowValueConstructorSyntaxInQuantifiedPredicates() {
		return true;
	}

	/**
	 * If the dialect supports {@link #supportsRowValueConstructorSyntax() row values},
	 * does it offer such support in IN lists as well?
	 * <p/>
	 * For example, "... where (FIRST_NAME, LAST_NAME) IN ( (?, ?), (?, ?) ) ..."
	 *
	 * @return True if this SQL dialect is known to support "row value
	 * constructor" syntax in the IN list; false otherwise.
	 */
	protected boolean supportsRowValueConstructorSyntaxInInList() {
		return true;
	}

	/**
	 * If the dialect supports {@link #supportsRowValueConstructorSyntax() row values},
	 * does it offer such support in IN subqueries as well?
	 * <p/>
	 * For example, "... where (FIRST_NAME, LAST_NAME) IN ( select ... ) ..."
	 *
	 * @return True if this SQL dialect is known to support "row value
	 * constructor" syntax in the IN subqueries; false otherwise.
	 */
	protected boolean supportsRowValueConstructorSyntaxInInSubQuery() {
		return supportsRowValueConstructorSyntaxInInList();
	}

	/**
	 * Some databases require a bit of syntactic noise when
	 * there are no tables in the from clause.
	 *
	 * @return the SQL equivalent to Oracle's {@code from dual}.
	 */
	protected String getFromDual() {
		return " from (values (0)) as dual";
	}

	protected String getFromDualForSelectOnly() {
		return "";
	}

	protected enum LockStrategy {
		CLAUSE,
		FOLLOW_ON,
		NONE;
	}

	protected static class ForUpdateClause {
		private LockMode lockMode;
		private int timeoutMillis = LockOptions.WAIT_FOREVER;
		private Map<String, String[]> keyColumnNames;
		private Map<String, String> aliases;

		public ForUpdateClause(LockMode lockMode) {
			this.lockMode = lockMode;
		}

		public ForUpdateClause() {
			this.lockMode = LockMode.NONE;
		}

		public void applyAliases(RowLockStrategy lockIdentifier, QuerySpec querySpec) {
			if ( lockIdentifier != RowLockStrategy.NONE ) {
				querySpec.getFromClause().visitTableGroups( tableGroup -> applyAliases( lockIdentifier, tableGroup ) );
			}
		}

		public void applyAliases(RowLockStrategy lockIdentifier, TableGroup tableGroup) {
			if ( aliases != null && lockIdentifier != RowLockStrategy.NONE ) {
				final String tableAlias = tableGroup.getPrimaryTableReference().getIdentificationVariable();
				if ( aliases.containsKey( tableGroup.getSourceAlias() ) ) {
					addAlias( tableGroup.getSourceAlias(), tableAlias );
					if ( lockIdentifier == RowLockStrategy.COLUMN ) {
						addKeyColumnNames( tableGroup );
					}
				}
			}
		}

		public LockMode getLockMode() {
			return lockMode;
		}

		public void setLockMode(LockMode lockMode) {
			if ( this.lockMode != LockMode.NONE && lockMode != this.lockMode ) {
				throw new QueryException( "mixed LockModes" );
			}
			this.lockMode = lockMode;
		}

		public void addKeyColumnNames(TableGroup tableGroup) {
			final String[] keyColumnNames = determineKeyColumnNames( tableGroup.getModelPart() );
			if ( keyColumnNames == null ) {
				throw new IllegalArgumentException( "Can't lock table group: " + tableGroup );
			}
			addKeyColumnNames(
					tableGroup.getSourceAlias(),
					tableGroup.getPrimaryTableReference().getIdentificationVariable(),
					keyColumnNames
			);
		}

		private String[] determineKeyColumnNames(ModelPart modelPart) {
			if ( modelPart instanceof Loadable ) {
				return ( (Loadable) modelPart ).getIdentifierColumnNames();
			}
			else if ( modelPart instanceof PluralAttributeMapping ) {
				return ((PluralAttributeMapping) modelPart).getCollectionDescriptor().getKeyColumnAliases( null );
			}
			else if ( modelPart instanceof EntityAssociationMapping ) {
				return determineKeyColumnNames( ( (EntityAssociationMapping) modelPart ).getAssociatedEntityMappingType() );
			}
			return null;
		}

		private void addKeyColumnNames(String alias, String tableAlias, String[] keyColumnNames) {
			if ( this.keyColumnNames == null ) {
				this.keyColumnNames = new HashMap<>();
			}
			this.keyColumnNames.put( tableAlias, keyColumnNames );
		}

		public boolean hasAlias(String alias) {
			return aliases != null && aliases.containsKey( alias );
		}

		private void addAlias(String alias, String tableAlias) {
			if ( aliases == null ) {
				aliases = new HashMap<>();
			}
			aliases.put( alias, tableAlias );
		}

		public int getTimeoutMillis() {
			return timeoutMillis;
		}

		public boolean hasAliases() {
			return aliases != null;
		}

		public void appendAliases(SqlAppender appender) {
			if ( aliases == null ) {
				return;
			}
			if ( keyColumnNames != null ) {
				boolean first = true;
				for ( String tableAlias : aliases.values() ) {
					final String[] keyColumns = keyColumnNames.get( tableAlias ); //use the id column alias
					if ( keyColumns == null ) {
						throw new IllegalArgumentException( "alias not found: " + tableAlias );
					}
					for ( String keyColumn : keyColumns ) {
						if ( first ) {
							first = false;
						}
						else {
							appender.appendSql( ", " );
						}
						appender.appendSql( tableAlias );
						appender.appendSql( '.' );
						appender.appendSql( keyColumn );
					}
				}
			}
			else {
				boolean first = true;
				for ( String tableAlias : aliases.values() ) {
					if ( first ) {
						first = false;
					}
					else {
						appender.appendSql( ", " );
					}
					appender.appendSql( tableAlias );
				}
			}
		}

		public String getAliases() {
			if ( aliases == null ) {
				return null;
			}
			return aliases.toString();
		}

		public void merge(LockOptions lockOptions) {
			if ( lockOptions != null ) {
				LockMode upgradeType = LockMode.NONE;
				if ( lockOptions.getAliasLockCount() == 0 ) {
					upgradeType = lockOptions.getLockMode();
				}
				else {
					for ( Map.Entry<String, LockMode> entry : lockOptions.getAliasSpecificLocks() ) {
						final LockMode lockMode = entry.getValue();
						if ( LockMode.READ.lessThan( lockMode ) ) {
							addAlias( entry.getKey(), null );
							if ( upgradeType != LockMode.NONE && lockMode != upgradeType ) {
								throw new QueryException( "mixed LockModes" );
							}
							upgradeType = lockMode;
						}
					}
				}
				lockMode = upgradeType;
				timeoutMillis = lockOptions.getTimeOut();
			}
		}
	}

}
