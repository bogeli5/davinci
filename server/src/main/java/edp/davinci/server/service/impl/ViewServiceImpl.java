/*
 * <<
 *  Davinci
 *  ==
 *  Copyright (C) 2016 - 2020 EDP
 *  ==
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  >>
 *
 */

package edp.davinci.server.service.impl;

import static edp.davinci.commons.Constants.AT_SIGN;
import static edp.davinci.commons.Constants.COMMA;
import static edp.davinci.server.commons.Constants.NO_AUTH_PERMISSION;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Stopwatch;

import edp.davinci.server.enums.*;
import edp.davinci.server.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edp.davinci.commons.Constants;
import edp.davinci.commons.util.CollectionUtils;
import edp.davinci.commons.util.JSONUtils;
import edp.davinci.commons.util.MD5Utils;
import edp.davinci.commons.util.StringUtils;
import edp.davinci.core.dao.entity.RelRoleView;
import edp.davinci.core.dao.entity.Source;
import edp.davinci.core.dao.entity.User;
import edp.davinci.core.dao.entity.View;
import edp.davinci.data.aggregator.AggregatorFactory;
import edp.davinci.data.aggregator.JdbcAggregator;
import edp.davinci.data.parser.ParserFactory;
import edp.davinci.data.parser.StatementParser;
import edp.davinci.data.pojo.ColumnModel;
import edp.davinci.data.pojo.PagingParam;
import edp.davinci.data.pojo.Param;
import edp.davinci.data.pojo.SqlQueryParam;
import edp.davinci.data.provider.DataProviderFactory;
import edp.davinci.data.util.SqlParseUtils;
import edp.davinci.server.dao.RelRoleViewExtendMapper;
import edp.davinci.server.dao.SourceExtendMapper;
import edp.davinci.server.dao.ViewExtendMapper;
import edp.davinci.server.dao.WidgetExtendMapper;
import edp.davinci.server.dto.project.ProjectDetail;
import edp.davinci.server.dto.project.ProjectPermission;
import edp.davinci.server.dto.source.SourceBaseInfo;
import edp.davinci.server.dto.view.AuthParamValue;
import edp.davinci.server.dto.view.RelRoleViewDTO;
import edp.davinci.server.dto.view.ViewBaseInfo;
import edp.davinci.server.dto.view.ViewCreate;
import edp.davinci.server.dto.view.ViewExecuteParam;
import edp.davinci.server.dto.view.ViewUpdate;
import edp.davinci.server.dto.view.ViewWithSource;
import edp.davinci.server.dto.view.ViewWithSourceBaseInfo;
import edp.davinci.server.dto.view.WidgetDistinctParam;
import edp.davinci.server.dto.view.WidgetQueryParam;
import edp.davinci.server.exception.NotFoundException;
import edp.davinci.server.exception.ServerException;
import edp.davinci.server.exception.UnAuthorizedExecption;
import edp.davinci.server.model.Paging;
import edp.davinci.server.model.PagingWithQueryColumns;
import edp.davinci.server.model.SqlVariable;
import edp.davinci.server.service.ProjectService;
import edp.davinci.server.service.ViewService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service("viewService")
public class ViewServiceImpl extends BaseEntityService implements ViewService {

    private static final Logger optLogger = LoggerFactory.getLogger(LogNameEnum.BUSINESS_OPERATION.getName());

    @Autowired
    private ViewExtendMapper viewExtendMapper;

    @Autowired
    private SourceExtendMapper sourceExtendMapper;

    @Autowired
    private WidgetExtendMapper widgetMapper;

    @Autowired
    private RelRoleViewExtendMapper relRoleViewExtendMapper;

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AuthVarUtils authVarUtils;

    @Value("${sql_template_delimiter:$}")
    private String sqlTempDelimiter;

    private static final String SQL_VARABLE_KEY = "name";

    private static final CheckEntityEnum entity = CheckEntityEnum.VIEW;

    private static final ExecutorService roleParamThreadPool = Executors.newFixedThreadPool(8);

    private static final int CONCURRENCY_EXPIRE = 60 * 60;

    @Override
    public boolean isExist(String name, Long id, Long projectId) {
        Long viewId = viewExtendMapper.getByNameWithProjectId(name, projectId);
        if (null != id && null != viewId) {
            return !id.equals(viewId);
        }
        return null != viewId && viewId.longValue() > 0L;
    }

    /**
     * 获取View列表
     *
     * @param projectId
     * @param user
     * @return
     */
    @Override
    public List<ViewBaseInfo> getViews(Long projectId, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(projectId, user, false);
        } catch (UnAuthorizedExecption e) {
            return null;
        }

        List<ViewBaseInfo> views = viewExtendMapper.getViewBaseInfoByProject(projectId);
        if (null == views) {
            return null;
        }

        if (isHiddenPermission(projectDetail, user)) {
            return null;
        }

        return views;
    }

    private boolean isHiddenPermission(ProjectDetail projectDetail, User user) {
        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        return projectPermission.getVizPermission() == UserPermissionEnum.HIDDEN.getPermission()
                && projectPermission.getWidgetPermission() == UserPermissionEnum.HIDDEN.getPermission()
                && projectPermission.getViewPermission() == UserPermissionEnum.HIDDEN.getPermission();
    }

    @Override
    public ViewWithSourceBaseInfo getView(Long id, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {
        ViewWithSourceBaseInfo view = viewExtendMapper.getViewWithSourceBaseInfo(id);
        if (null == view) {
            throw new NotFoundException("View is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(view.getProjectId(), user, false);
        if (isHiddenPermission(projectDetail, user)) {
            throw new UnAuthorizedExecption("Insufficient permissions");
        }

        List<RelRoleView> relRoleViews = relRoleViewExtendMapper.getByView(view.getId());
        view.setRoles(relRoleViews);
        return view;
    }

    /**
     * 新建View
     *
     * @param viewCreate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public ViewWithSourceBaseInfo createView(ViewCreate viewCreate, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {

        Long projectId = viewCreate.getProjectId();
        checkWritePermission(entity, projectId, user, "create");

        String name = viewCreate.getName();
        if (isExist(name, null, projectId)) {
            alertNameTaken(entity, name);
        }

        Long sourceId = viewCreate.getSourceId();
        Source source = getSource(sourceId);

        if (!DataProviderFactory.getProvider(source.getType()).test(source, user)) {
            throw new ServerException("Get source connection fail");
        }

        BaseLock lock = getLock(entity, name, projectId);
        if (lock != null && !lock.getLock()) {
            alertNameTaken(entity, name);
        }

        try {
            View view = new View();
            view.setCreateBy(user.getId());
            view.setCreateTime(new Date());
            BeanUtils.copyProperties(viewCreate, view);

            insertView(view);
	        optLogger.info(OptLogUtils.insert(TableTypeEnum.VIEW, view));

	        if (!CollectionUtils.isEmpty(viewCreate.getRoles()) && !StringUtils.isEmpty(viewCreate.getVariable())) {
                insertRelRoleView(viewCreate.getVariable(), viewCreate.getRoles(), user, view);
            }

            SourceBaseInfo sourceBaseInfo = new SourceBaseInfo();
            BeanUtils.copyProperties(source, sourceBaseInfo);

            ViewWithSourceBaseInfo viewWithSource = new ViewWithSourceBaseInfo();
            BeanUtils.copyProperties(view, viewWithSource);

            viewWithSource.setSource(sourceBaseInfo);
            return viewWithSource;
        } finally {
            releaseLock(lock);
        }
    }

    @Transactional
    protected void insertView(View view) {
        if (viewExtendMapper.insert(view) <= 0) {
            throw new ServerException("Create view fail");
        }
    }

    private Source getSource(Long id) {
        Source source = sourceExtendMapper.selectByPrimaryKey(id);
        if (null == source) {
            log.error("Source({}) not found", id);
            throw new NotFoundException("Source is not found");
        }
        return source;
    }

    /**
     * 更新View
     *
     * @param viewUpdate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public boolean updateView(ViewUpdate viewUpdate, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {

        Long id = viewUpdate.getId();
        View view = getView(id);

        Long projectId = view.getProjectId();
        checkWritePermission(entity, projectId, user, "update");

        String name = viewUpdate.getName();
        if (isExist(name, id, projectId)) {
            alertNameTaken(entity, name);
        }

        Source source = getSource(view.getSourceId());

        // 测试连接
        if (!DataProviderFactory.getProvider(source.getType()).test(source, user)) {
            throw new ServerException("Get source connection fail");
        }

        BaseLock lock = getLock(entity, name, projectId);
        if (lock != null && !lock.getLock()) {
            alertNameTaken(entity, name);
        }

        try {
	        View originView = new View();
	        BeanUtils.copyProperties(view, originView);
            BeanUtils.copyProperties(viewUpdate, view);
            view.setUpdateBy(user.getId());
            view.setUpdateTime(new Date());

            updateView(view);
	        optLogger.info(OptLogUtils.update(TableTypeEnum.VIEW, originView, view));

            if (CollectionUtils.isEmpty(viewUpdate.getRoles())) {
                relRoleViewExtendMapper.deleteByViewId(id);
            }

            if (!StringUtils.isEmpty(viewUpdate.getVariable())) {
                insertRelRoleView(viewUpdate.getVariable(), viewUpdate.getRoles(), user, view);
            }

            return true;

        } finally {
            releaseLock(lock);
        }
    }

    @Transactional
    protected void updateView(View view) {
        if (viewExtendMapper.update(view) <= 0) {
            throw new ServerException("Update view fail");
        }
    }

    private View getView(Long id) {
        View view = viewExtendMapper.selectByPrimaryKey(id);
        if (null == view) {
            log.error("View({}) not found", id);
            throw new NotFoundException("View is not found");
        }
        return view;
    }

    /**
     * 删除View
     *
     * @param id
     * @param user
     * @return
     */
    @Override
    @Transactional
    public boolean deleteView(Long id, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        View view = getView(id);

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(view.getProjectId(), user, false);
        } catch (UnAuthorizedExecption e) {
            alertUnAuthorized(entity, user, "delete");
        }

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getViewPermission() < UserPermissionEnum.DELETE.getPermission()) {
            alertUnAuthorized(entity, user, "delete");
        }

        if (!CollectionUtils.isEmpty(widgetMapper.getWidgetsByWiew(id))) {
            throw new ServerException(
                    "The current view has been referenced, please delete the reference and then operate");
        }

        if (viewExtendMapper.deleteByPrimaryKey(id) <= 0) {
            throw new ServerException("Delete view fail");
        }

	    optLogger.info(OptLogUtils.delete(TableTypeEnum.VIEW, view));
	    relRoleViewExtendMapper.deleteByViewId(id);
        return true;
    }

    /**
     * 执行sql
     * 
     * @param executeParam
     * @param user
     * @return
     * @throws NotFoundException
     * @throws UnAuthorizedExecption
     * @throws ServerException
     */
    public PagingWithQueryColumns execute(ViewExecuteParam executeParam, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {

        Source source = getSource(executeParam.getSourceId());

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(source.getProjectId(), user, false);
        } catch (UnAuthorizedExecption e) {
            throw new UnAuthorizedExecption("You have not permission to execute sql");
        }

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getSourcePermission() == UserPermissionEnum.HIDDEN.getPermission()
                || projectPermission.getViewPermission() < UserPermissionEnum.WRITE.getPermission()) {
            throw new UnAuthorizedExecption("You have not permission to execute sql");
        }

        boolean isMaintainer = isMaintainer(user, projectDetail);

        PagingWithQueryColumns pagingWithQueryColumns = new PagingWithQueryColumns();
        try {

            StatementParser parser = ParserFactory.getParser(source.getType());

            String statement = executeParam.getSql();

            SqlQueryParam queryParam = SqlQueryParam.builder().limit(executeParam.getLimit())
                    .pageNo(executeParam.getPageNo()).pageSize(executeParam.getPageSize()).isMaintainer(isMaintainer)
                    .nativeQuery(true).type("query").build();

            statement = parser.parseSystemVars(statement, queryParam, source, user);
            statement = parser.parseAuthVars(statement, queryParam, null, null, source, user);

            Map<String, Object> queryParams = new HashMap<>();
            List<SqlVariable> variables = executeParam.getVariables();
            setQueryVarValue(queryParams, variables, null);
            statement = parser.parseQueryVars(statement, queryParam, queryParams, null, source, user);

            List<String> executeStatements = parser.getExecuteStatement(statement, queryParam, source, user);
            List<String> queryStatements = parser.getQueryStatement(statement, queryParam, source, user);

            if (!CollectionUtils.isEmpty(executeStatements)) {
                executeStatements.forEach(s -> {
                    DataUtils.execute(source, s, user);
                });
            }

            if (!CollectionUtils.isEmpty(queryStatements)) {
                for (String s : queryStatements) {
                    PagingParam paging = new PagingParam(0, 0, queryParam.getLimit());
                    pagingWithQueryColumns = DataUtils.syncQuery4Paging(source, s, paging, new HashSet<String>(), user);
                }
            }

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new ServerException(e.getMessage());
        }

        return pagingWithQueryColumns;
    }

    private void setQueryVarValue(Map<String, Object> queryParams, List<SqlVariable> variables, List<Param> params) {
        variables.forEach(var -> {
            SqlVariableTypeEnum typeEnum = SqlVariableTypeEnum.typeOf(var.getType());
            if (typeEnum != SqlVariableTypeEnum.QUERYVAR) {
                return;
            }

            String varName = var.getName().trim();
            Object value = null;
            if (!CollectionUtils.isEmpty(params)) {
                Param param = params.stream().filter(p -> p.getName().equals(varName)).findFirst().get();
                value = SqlVariableValueTypeEnum.getValue(var.getValueType(), param.getValue(), var.isUdf());
            }

            if (value == null) {
                queryParams.put(varName,
                        SqlVariableValueTypeEnum.getValues(var.getValueType(), var.getDefaultValues(), var.isUdf()));
                return;
            }

            if (value instanceof List && ((List) value).size() > 0) {
                value = ((List) value).stream().collect(Collectors.joining(COMMA)).toString();
            }

            queryParams.put(varName, value);
        });
    }

    private boolean isMaintainer(User user, ProjectDetail projectDetail) {
        return projectService.isMaintainer(projectDetail, user);
    }

    /**
     * 返回view源数据集
     *
     * @param id
     * @param queryParam
     * @param user
     * @return
     */
    @Override
    public Paging<Map<String, Object>> getData(Long id, WidgetQueryParam queryParam, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {

        if (CollectionUtils.isEmpty(queryParam.getGroups())
                && CollectionUtils.isEmpty(queryParam.getAggregators())) {
            return null;
        }

        ViewWithSource viewWithSource = getViewWithSource(id);
        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);
        if (!projectService.allowGetData(projectDetail, user)) {
            alertUnAuthorized(entity, user, "get data from");
        }

        if (viewWithSource.getSource() == null) {
            throw new NotFoundException("Source is not found");
        }

        if (StringUtils.isEmpty(viewWithSource.getSql())) {
            return null;
        }

        return getPagingData(projectService.isMaintainer(projectDetail, user), viewWithSource, queryParam, user);
    }

    private ViewWithSource getViewWithSource(Long id) {
        ViewWithSource viewWithSource = viewExtendMapper.getViewWithSource(id);
        if (null == viewWithSource) {
            log.info("View({}) not found", id);
            throw new NotFoundException("View is not found");
        }
        return viewWithSource;
    }

    private PagingWithQueryColumns getPagingData(boolean isMaintainer, ViewWithSource viewWithSource,
            WidgetQueryParam param, User user) throws ServerException {

        PagingWithQueryColumns pagingWithQueryColumns = null;

        boolean withCache = param.getCache() && param.getExpired() > 0L;
        
        ConcurrencyStrategyEnum strategy = ConcurrencyStrategyEnum.strategyOf(param.getConcurrencyOptimizationStrategy());
        boolean withConcurrency = param.isConcurrencyOptimization() && strategy != null;
        
        Map<String, Object> configMap = JSONUtils.toObject(viewWithSource.getConfig(), Map.class);
        boolean withAggregator = !CollectionUtils.isEmpty(configMap) && "local".equals(configMap.get("aggregator"));

        String cacheKey = null;
        try {

            Source source = viewWithSource.getSource();
            List<Param> params = param.getParams();
            List<SqlVariable> variables = getVariables(viewWithSource.getVariable());
            List<RelRoleView> roleViewList = relRoleViewExtendMapper.getByUserAndView(user.getId(),
                    viewWithSource.getId());
            String statement = viewWithSource.getSql();
            
            StatementParser parser = ParserFactory.getParser(source.getType());
            SqlQueryParam sqlQueryParam = SqlQueryParam.builder()
                                            .limit(param.getLimit())
                                            .pageNo(param.getPageNo())
                                            .pageSize(param.getPageSize())
                                            .isMaintainer(isMaintainer)
                                            .nativeQuery(param.isNativeQuery())
                                            .aggregators(param.getAggregators())
                                            .groups(param.getGroups())
                                            .filters(param.getFilters())
                                            .type(param.getType())
                                            .build();
            
            if("distinct".equals(param.getType())) {
                sqlQueryParam.setColumns(((WidgetDistinctParam)param).getColumns());
            }

            String sWithSysVar = parser.parseSystemVars(statement, sqlQueryParam, source, user);

            Map<String, List<String>> authParams = new HashMap<>();
            if (isMaintainer) {
                authParams = null;
            }else{
                setAuthVarValue(authParams, variables, roleViewList, user);
            }

            Map<String, Object> queryParams = new HashMap<>();
            setQueryVarValue(queryParams, variables, params);

            // parse auth var first
            String sWithAuthVar = parser.parseAuthVars(sWithSysVar, sqlQueryParam, authParams, queryParams, source, user);
            String sWithQueryVar = parser.parseQueryVars(sWithAuthVar, sqlQueryParam, queryParams, authParams, source, user);

            List<String> executeStatements = parser.getExecuteStatement(sWithQueryVar, sqlQueryParam, source, user);
            List<String> queryStatements = parser.getQueryStatement(sWithQueryVar, sqlQueryParam, source, user);

            if (withCache) {
                cacheKey = getCacheKey(source, queryStatements.get(queryStatements.size() - 1), param);
                if (!param.getFlush()) {
                    pagingWithQueryColumns = getPagingDataByCache(cacheKey);
                    if (pagingWithQueryColumns != null) {
                        return pagingWithQueryColumns;
                    }
                }
            }

            // execute statement with all var
            if (!CollectionUtils.isEmpty(executeStatements)) {
                executeStatements.forEach(s -> {
                    DataUtils.execute(source, s, user);
                });
            }

            // get exclude columns
            Set<String> excludeColumns = new HashSet<>();
            Set<String> columns = getExcludeColumns(roleViewList);
            if (!CollectionUtils.isEmpty(columns) && !isMaintainer) {
                excludeColumns.addAll(columns);
            }

            BaseLock lock = null;
            if (withConcurrency) {
                String concurrencyKey = getConcurrencyKey(source, queryStatements.get(queryStatements.size() - 1), param);
                
                // try to get data from concurrency cache first
                pagingWithQueryColumns = getPagingDataByCache(concurrencyKey);
                if (pagingWithQueryColumns != null) {
                    return pagingWithQueryColumns;
                }

                lock = getConcurrencyStrategyLock(concurrencyKey, strategy);
                if (lock != null) {// query data

                    try {
                        
                        Stopwatch watch = Stopwatch.createStarted();
                        
                        if (withAggregator) {
                            // parse auth var first but with out values
                            sWithAuthVar = parser.parseAuthVars(sWithSysVar, sqlQueryParam, null, queryParams, source, user);
                            sWithQueryVar = parser.parseQueryVars(sWithAuthVar, sqlQueryParam, queryParams, authParams, source, user);
                            pagingWithQueryColumns = getPagingDataByAggregator(sWithQueryVar, sqlQueryParam, authParams, excludeColumns, viewWithSource, user);
                        
                        } else {
                            pagingWithQueryColumns = doQuery(new PagingParam(sqlQueryParam.getPageNo(),
                                    sqlQueryParam.getPageSize(), sqlQueryParam.getLimit()), queryStatements,
                                    excludeColumns, source, user);
                        }

                        redisUtils.set(concurrencyKey, pagingWithQueryColumns, Math.max(10_000, watch.elapsed(TimeUnit.MILLISECONDS)), TimeUnit.MILLISECONDS);
                    
                    }finally {
                        lock.release();
                    }

                }else{// data is querying so pending
                    while(LockFactory.ifLockExist(concurrencyKey, LockType.REDIS)) {
                        Thread.sleep(1_000);
                        pagingWithQueryColumns = getPagingDataByCache(concurrencyKey);
                        if (pagingWithQueryColumns != null) {
                            return pagingWithQueryColumns;
                        }
                    }

                    return getPagingDataByCache(concurrencyKey);
                }
            
            }else{
                if (withAggregator) {
                    // parse auth var first but with out values
                    sWithAuthVar = parser.parseAuthVars(sWithSysVar, sqlQueryParam, null, queryParams, source, user);
                    sWithQueryVar = parser.parseQueryVars(sWithAuthVar, sqlQueryParam, queryParams, authParams, source, user);
                    pagingWithQueryColumns = getPagingDataByAggregator(sWithQueryVar, sqlQueryParam, authParams, excludeColumns, viewWithSource, user);
                } else {
                    pagingWithQueryColumns = doQuery(new PagingParam(sqlQueryParam.getPageNo(),
                            sqlQueryParam.getPageSize(), sqlQueryParam.getLimit()), queryStatements, excludeColumns,
                            source, user);
                }
            }

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new ServerException(e.getMessage());
        }

        if (withCache) {
            redisUtils.set(cacheKey, pagingWithQueryColumns, param.getExpired(), TimeUnit.SECONDS);
        }

        return pagingWithQueryColumns;
    }

    private PagingWithQueryColumns doQuery(PagingParam paging, List<String> queryStatements,
            Set<String> excludeColumns, Source source, User user) {
        PagingWithQueryColumns pagingWithQueryColumns = null;
        for (String s : queryStatements) {
            pagingWithQueryColumns = DataUtils.syncQuery4Paging(source, s, paging, excludeColumns, user);
        }
        return pagingWithQueryColumns;
    }

    public List<SqlVariable> getVariables(String variable) {

		if (StringUtils.isEmpty(variable)) {
			return Collections.emptyList();
		}

		try {
			return JSONUtils.toObjectArray(variable, SqlVariable.class);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return Collections.emptyList();
	}

    private void setAuthVarValue(Map<String, List<String>> authParams, List<SqlVariable> variables,
            List<RelRoleView> roleViewList, User user) {

        for (SqlVariable var : variables) {
            SqlVariableTypeEnum typeEnum = SqlVariableTypeEnum.typeOf(var.getType());
            if (typeEnum != SqlVariableTypeEnum.AUTHVAR) {
                continue;
            }

            String varName = var.getName().trim();

            roleViewList.forEach(r -> {
                List<AuthParamValue> paramValues = JSONUtils.toObjectArray(r.getRowAuth(), AuthParamValue.class);
                paramValues.stream().filter(paramValue -> paramValue.isEnable() && paramValue.getName().equals(varName))
                        .findFirst().ifPresent(paramValue -> {
                            var.setDefaultValues(paramValue.getValues());
                        });
            });

            List<String> values = authVarUtils.getValue(var, user.getEmail());
            if (values == null) {
                authParams.put(varName, Arrays.asList(new String[] { NO_AUTH_PERMISSION }));
            } else if (!values.isEmpty()) {
                authParams.put(varName, values);
            }
        }
    }

    private String getCacheKey(Source source, String sql, WidgetQueryParam executeParam) {
        String md5 = MD5Utils.getMD5(sql, true, 16);
        return "CACHE:" + source.getId() + AT_SIGN + md5 + AT_SIGN + executeParam.getPageNo() + AT_SIGN
                + executeParam.getPageSize() + AT_SIGN + executeParam.getLimit();
    }

    private String getConcurrencyKey(Source source, String sql, WidgetQueryParam executeParam) {
        String md5 = MD5Utils.getMD5(sql, true, 16);
        return "CONCURRENCY:" + source.getId() + AT_SIGN + md5 + AT_SIGN + executeParam.getPageNo() + AT_SIGN
                + executeParam.getPageSize() + AT_SIGN + executeParam.getLimit();
    }

    private PagingWithQueryColumns getPagingDataByCache(String key) {
        try {
            return (PagingWithQueryColumns) redisUtils.get(key);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        return null;
    }

    private BaseLock getConcurrencyStrategyLock(String cacheKey, ConcurrencyStrategyEnum strategy) {
        
        BaseLock lock = LockFactory.getLock(cacheKey, CONCURRENCY_EXPIRE, LockType.REDIS);

        if (ConcurrencyStrategyEnum.FAIL_FAST == strategy) {
            if (lock != null && lock.getLock()) {
                return lock;
            }
            throw new ServerException("The data is querying, please try again later");
        }

        if (ConcurrencyStrategyEnum.DIRTY_READ == strategy) {
            if (lock != null && lock.getLock()) {
                return lock;
            }
        }
        
        return null;
    }

    /**
     * @param statement the query statement with out auth var
     */
    private PagingWithQueryColumns getPagingDataByAggregator(String statement, SqlQueryParam queryParam,
            Map<String, List<String>> authParams, Set<String> excludeColumns, ViewWithSource viewWithSource,
            User user) {

        Stopwatch watch = Stopwatch.createStarted();

        Source source = viewWithSource.getSource();
        StatementParser parser = ParserFactory.getParser(source.getType());
        JdbcAggregator aggregator = (JdbcAggregator) AggregatorFactory.getAggregator("jdbc");

        // build query sql only with system var
        List<String> queryStatements = parser.getQueryStatement(statement,
                SqlQueryParam.builder().type("query").build(), source, user);

        // get table name like T_md5(sourceId + @ + sql)
        String table = "T_"
                + MD5Utils.getMD5(source.getId() + AT_SIGN + queryStatements.get(queryStatements.size() - 1), true, 16);
        
        if (!aggregator.getDataTable(table).isTtl()) {
            return queryByAggregator(table, queryParam, authParams, excludeColumns, viewWithSource, user);
        }

        PagingWithQueryColumns pagingWithQueryColumns = null;
        // query from source
        for (String s : queryStatements) {
            pagingWithQueryColumns = DataUtils.syncQuery4Paging(source, s, new PagingParam(0, 0, queryParam.getLimit()),
                    Collections.emptySet(), user);
        }

        String viewModel = viewWithSource.getModel();
        Map<String, ColumnModel> viewModelMap = JSONUtils.toObject(viewModel,
                new TypeReference<Map<String, ColumnModel>>() {
                });

        // get header from view model
        List<ColumnModel> header = viewModelMap.keySet().stream().map(k -> {
            ColumnModel m = viewModelMap.get(k);
            m.setName(k);
            return m;
        }).collect(Collectors.toList());

        // get data from pagingWithQueryColumns
        List<List<Object>> data = new ArrayList<>();
        pagingWithQueryColumns.getResultList().forEach(r -> {
            List<Object> row = new ArrayList<>();
            header.forEach(h -> {
                row.add(r.get(h.getName()));
            });
            data.add(row);
        });

        // load data to aggregator table
        aggregator.loadData(table, header, data, watch.elapsed(TimeUnit.MILLISECONDS));

        // query
        return queryByAggregator(table, queryParam, authParams, excludeColumns, viewWithSource, user);
    }

    private PagingWithQueryColumns queryByAggregator(String table, SqlQueryParam queryParam,
            Map<String, List<String>> authParams, Set<String> excludeColumns, ViewWithSource viewWithSource,
            User user) {
        
        JdbcAggregator aggregator = (JdbcAggregator) AggregatorFactory.getAggregator("jdbc");
        Source source = aggregator.getSource();
        StatementParser parser = ParserFactory.getParser(aggregator.getAggregatorType());
        
        // aggregator original query sql with out aggregation
        String sql = "select * from " + table;
        String viewStatement = viewWithSource.getSql();
        Set<String> expSet = SqlParseUtils.getAuthExpression(viewStatement, sqlTempDelimiter);
        if (!CollectionUtils.isEmpty(expSet)) {
            Map<String, String> expMap = SqlParseUtils.getAuthParsedExp(expSet, sqlTempDelimiter, authParams);
            int i = 0;
            for (String value : expMap.values()) {
                if (i == 0) {
                    sql += (" where " + value);
                    continue;
                }
                sql += (" and " + value);
                i++;
            }
        }

        // build query sql with aggregation
        List<String> queryStatements = parser.getQueryStatement(sql, queryParam, source, user);
        return doQuery(new PagingParam(queryParam.getPageNo(), queryParam.getPageSize(), queryParam.getLimit()),
                queryStatements, excludeColumns, source, user);
    }

    /**
     * 获取结果集
     *
     * @param isMaintainer
     * @param viewWithSource
     * @param executeParam
     * @param user
     * @return
     * @throws ServerException
     */
    @Override
    public PagingWithQueryColumns getDataWithQueryColumns(boolean isMaintainer, ViewWithSource viewWithSource,
            WidgetQueryParam executeParam, User user) throws ServerException {
        return getPagingData(isMaintainer, viewWithSource, executeParam, user);
    }

    @Override
    public List<Map<String, Object>> getDistinctValue(Long id, WidgetDistinctParam param, User user)
            throws NotFoundException, ServerException, UnAuthorizedExecption {
        ViewWithSource viewWithSource = getViewWithSource(id);
        if (viewWithSource.getSource() == null) {
            throw new NotFoundException("Source is not found");
        }

        if (StringUtils.isEmpty(viewWithSource.getSql())) {
            return null;
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);
        if (!projectService.allowGetData(projectDetail, user)) {
            alertUnAuthorized(entity, user, "get data from");
        }

        return getDistinctValue(projectService.isMaintainer(projectDetail, user), viewWithSource, param, user);
    }

    private List<Map<String, Object>> getDistinctValue(boolean isMaintainer, ViewWithSource viewWithSource,
            WidgetDistinctParam param, User user) throws ServerException {
        return getPagingData(isMaintainer, viewWithSource, param, user).getResultList();
    }

    private Set<String> getExcludeColumns(List<RelRoleView> roleViewList) {
        if (CollectionUtils.isEmpty(roleViewList)) {
            return null;
        }

        Set<String> columns = new HashSet<>();
        boolean isFullAuth = false;
        for (RelRoleView r : roleViewList) {
            if (!StringUtils.isEmpty(r.getColumnAuth())) {
                columns.addAll(JSONUtils.toObjectArray(r.getColumnAuth(), String.class));
            } else {
                isFullAuth = true;
                break;
            }
        }
        return isFullAuth ? null : columns;
    }

    private void insertRelRoleView(String sqlVarible, List<RelRoleViewDTO> roles, User user, View view) {

        List<SqlVariable> variables = JSONUtils.toObjectArray(sqlVarible, SqlVariable.class);
        if (CollectionUtils.isEmpty(roles)) {
            relRoleViewExtendMapper.deleteByViewId(view.getId());
            return;
        }

        roleParamThreadPool.submit(() -> {
            Set<String> vars = null, columns = null;

            if (!CollectionUtils.isEmpty(variables)) {
                vars = variables.stream().map(SqlVariable::getName).collect(Collectors.toSet());
            }
            if (!StringUtils.isEmpty(view.getModel())) {
                columns = JSONUtils.toObject(view.getModel(), Map.class).keySet();
            }

            Set<String> finalColumns = columns;
            Set<String> finalVars = vars;

            List<RelRoleView> relRoleViews = new ArrayList<>();
            roles.forEach(r -> {
                if (r.getRoleId().longValue() <= 0L) {
                    return;
                }

                String rowAuth = null, columnAuth = null;
                if (!StringUtils.isEmpty(r.getRowAuth())) {
                    List<Map> rowAuthList = JSONUtils.toObjectArray(r.getRowAuth(), Map.class);
                    if (!CollectionUtils.isEmpty(rowAuthList)) {
                        List<Map> newRowAuthList = new ArrayList<Map>();
                        for (Map jsonMap : rowAuthList) {
                            String name = (String) jsonMap.get(SQL_VARABLE_KEY);
                            if (finalVars.contains(name)) {
                                newRowAuthList.add(jsonMap);
                            }
                        }
                        rowAuth = JSONUtils.toString(newRowAuthList);
                        newRowAuthList.clear();
                    }
                }

                if (null != finalColumns && !StringUtils.isEmpty(r.getColumnAuth())) {
                    List<String> clms = JSONUtils.toObjectArray(r.getColumnAuth(), String.class);
                    List<String> collect = clms.stream().filter(c -> finalColumns.contains(c))
                            .collect(Collectors.toList());
                    columnAuth = JSONUtils.toString(collect);
                }

                RelRoleView relRoleView = new RelRoleView();
                relRoleView.setRoleId(r.getRoleId());
                relRoleView.setViewId(view.getId());
                relRoleView.setRowAuth(rowAuth);
                relRoleView.setColumnAuth(columnAuth);
                relRoleView.setCreateBy(user.getId());
                relRoleView.setCreateTime(new Date());
                relRoleViews.add(relRoleView);
            });

            if (!CollectionUtils.isEmpty(relRoleViews)) {
                relRoleViewExtendMapper.insertBatch(relRoleViews);
            }
        });
    }

    @Override
    public PagingWithQueryColumns getDataWithQueryColumns(Long id, WidgetQueryParam executeParam, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {
        return (PagingWithQueryColumns) getData(id, executeParam, user);
    }

    @Override
    public String showSql(Long id, WidgetQueryParam queryParam, User user)
            throws NotFoundException, UnAuthorizedExecption, ServerException {
        
        ViewWithSource viewWithSource = getViewWithSource(id);
        
        if (StringUtils.isEmpty(viewWithSource.getSql())) {
            return null;
        }

        Source source = viewWithSource.getSource();
        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);
        boolean isMaintainer = projectService.isMaintainer(projectDetail, user);

        List<Param> params = queryParam.getParams();
        List<SqlVariable> variables = getVariables(viewWithSource.getVariable());
        List<RelRoleView> roleViewList = relRoleViewExtendMapper.getByUserAndView(user.getId(),
                viewWithSource.getId());
        String statement = viewWithSource.getSql();
        
        StatementParser parser = ParserFactory.getParser(source.getType());
        SqlQueryParam sqlQueryParam = SqlQueryParam.builder()
                                        .limit(queryParam.getLimit())
                                        .pageNo(queryParam.getPageNo())
                                        .pageSize(queryParam.getPageSize())
                                        .isMaintainer(isMaintainer)
                                        .nativeQuery(queryParam.isNativeQuery())
                                        .aggregators(queryParam.getAggregators())
                                        .groups(queryParam.getGroups())
                                        .filters(queryParam.getFilters())
                                        .type(queryParam.getType())
                                        .build();
        
        if("distinct".equals(queryParam.getType())) {
            sqlQueryParam.setColumns(((WidgetDistinctParam)queryParam).getColumns());
        }

        String sWithSysVar = parser.parseSystemVars(statement, sqlQueryParam, source, user);

        Map<String, List<String>> authParams = new HashMap<>();
        if (isMaintainer) {
            authParams = null;
        }else{
            setAuthVarValue(authParams, variables, roleViewList, user);
        }

        Map<String, Object> queryParams = new HashMap<>();
        setQueryVarValue(queryParams, variables, params);

        // parse auth var first
        String sWithAuthVar = parser.parseAuthVars(sWithSysVar, sqlQueryParam, authParams, queryParams, source, user);
        
        String sWithQueryVar = parser.parseQueryVars(sWithAuthVar, sqlQueryParam, queryParams, authParams, source, user);

        List<String> executeStatements = parser.getExecuteStatement(sWithQueryVar, sqlQueryParam, source, user);
        List<String> queryStatements = parser.getQueryStatement(sWithQueryVar, sqlQueryParam, source, user);
        
        String sql = String.join(Constants.NEW_LINE, executeStatements);
        sql += String.join(Constants.NEW_LINE, queryStatements);
        return sql;
    }

}