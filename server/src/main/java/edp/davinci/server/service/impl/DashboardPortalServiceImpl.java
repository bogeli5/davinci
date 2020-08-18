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

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import edp.davinci.server.enums.*;
import edp.davinci.server.util.OptLogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edp.davinci.server.dao.MemDashboardWidgetExtendMapper;
import edp.davinci.server.dao.RelRoleDashboardWidgetExtendMapper;
import edp.davinci.server.dto.dashboard.DashboardPortalCreate;
import edp.davinci.server.dto.dashboard.DashboardPortalUpdate;
import edp.davinci.server.dto.project.ProjectPermission;
import edp.davinci.server.dto.role.VizVisibility;
import edp.davinci.server.exception.NotFoundException;
import edp.davinci.server.exception.ServerException;
import edp.davinci.server.exception.UnAuthorizedExecption;
import edp.davinci.core.dao.entity.User;
import edp.davinci.server.service.DashboardPortalService;
import edp.davinci.server.service.ProjectService;
import edp.davinci.server.util.BaseLock;
import edp.davinci.commons.util.CollectionUtils;
import edp.davinci.core.dao.entity.DashboardPortal;
import edp.davinci.core.dao.entity.RelRolePortal;
import edp.davinci.core.dao.entity.Role;
import lombok.extern.slf4j.Slf4j;

@Service("dashboardPortalService")
@Slf4j
public class DashboardPortalServiceImpl extends VizCommonService implements DashboardPortalService {

	private static final Logger optLogger = LoggerFactory.getLogger(LogNameEnum.BUSINESS_OPERATION.getName());

    @Autowired
    private ProjectService projectService;
    
    @Autowired
    private RelRoleDashboardWidgetExtendMapper relRoleDashboardWidgetExtendMapper;

    @Autowired
    private MemDashboardWidgetExtendMapper memDashboardWidgetExtendMapper;
    
    private static final CheckEntityEnum entity = CheckEntityEnum.DASHBOARDPORTAL;

    @Override
    public boolean isExist(String name, Long id, Long projectId) {
        Long portalId = dashboardPortalExtendMapper.getByNameWithProjectId(name, projectId);
        if (null != id && null != portalId) {
            return id.longValue() != portalId.longValue();
        }
        return null != portalId && portalId.longValue() > 0L;
    }
    
    private void checkIsExist(String name, Long id, Long projectId) {
        if (isExist(name, id, projectId)) {
            alertNameTaken(entity, name);
        }
    }

    /**
     * 获取DashboardPortal列表
     *
     * @param projectId
     * @param user
     * @return
     */
    @Override
    public List<DashboardPortal> getDashboardPortals(Long projectId, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        if (!checkReadPermission(entity, projectId, user)) {
            return null;
        }

        List<DashboardPortal> dashboardPortals = dashboardPortalExtendMapper.getByProject(projectId);

        if (!CollectionUtils.isEmpty(dashboardPortals)) {
        	
        	ProjectPermission projectPermission = getProjectPermission(projectId, user);

            List<Long> allPortals = dashboardPortals.stream().map(DashboardPortal::getId).collect(Collectors.toList());

            List<Long> disablePortals = getDisableVizs(user.getId(), projectId, allPortals, VizEnum.PORTAL);

            Iterator<DashboardPortal> iterator = dashboardPortals.iterator();

            while (iterator.hasNext()) {
                DashboardPortal portal = iterator.next();
                boolean disable = isDisableVizs(projectPermission, disablePortals, portal.getId());
                boolean noPublish = projectPermission.getVizPermission() < UserPermissionEnum.WRITE.getPermission() && !portal.getPublish();
                if (disable || noPublish) {
                    iterator.remove();
                }
            }
        }

        return dashboardPortals;
    }
    
	private DashboardPortal getDashboardPortal(Long id) {
		
		DashboardPortal dashboardPortal = dashboardPortalExtendMapper.selectByPrimaryKey(id);
        
		if (null == dashboardPortal) {
			log.warn("DashboardPortal({}) is not found", id);
            throw new NotFoundException("DashboardPortal is not found");
        }

		return dashboardPortal;
	}
	
    /**
     * 新建DashboardPortal
     *
     * @param dashboardPortalCreate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public DashboardPortal createDashboardPortal(DashboardPortalCreate dashboardPortalCreate, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

    	Long projectId = dashboardPortalCreate.getProjectId();
    	checkWritePermission(entity, projectId, user, "create");

    	String name = dashboardPortalCreate.getName();
    	checkIsExist(name, null, projectId);
        
        BaseLock lock = getLock(entity, name, projectId);
        if (!lock.getLock()) {
        	alertNameTaken(entity, name);
        }

		try {
			DashboardPortal dashboardPortal = new DashboardPortal();
			dashboardPortal.setCreateBy(user.getId());
			dashboardPortal.setCreateTime(new Date());
			BeanUtils.copyProperties(dashboardPortalCreate, dashboardPortal);

			insertDashboardPortal(dashboardPortal, dashboardPortalCreate.getRoleIds(), user);
			optLogger.info(OptLogUtils.insert(TableTypeEnum.DASHBOARD_PORTAL, dashboardPortal));


			return dashboardPortal;
		} finally {
			releaseLock(lock);
		}
    }
    
    @Transactional
    protected void insertDashboardPortal(DashboardPortal dashboardPortal, List<Long> roleIds, User user) {

    	if (dashboardPortalExtendMapper.insertSelective(dashboardPortal) != 1) {
			throw new ServerException("Create dashboardPortal fail");
		}
		
		if (!CollectionUtils.isEmpty(roleIds)) {
			List<Role> roles = roleMapper.getRolesByIds(roleIds);
			List<RelRolePortal> rels = roles.stream()
					.map(r -> {
						RelRolePortal rel = new RelRolePortal();
						rel.setRoleId(r.getId());
						rel.setPortalId(dashboardPortal.getId());
						rel.setCreateBy(user.getId());
						rel.setUpdateTime(new Date());
						rel.setVisible(false);
						return rel;
					})
					.collect(Collectors.toList());

			if (!CollectionUtils.isEmpty(rels)) {
				relRolePortalExtendMapper.insertBatch(rels);
				optLogger.info(OptLogUtils.insertBatch(TableTypeEnum.DASHBOARD_PORTAL, rels));
			}
		}
    }


	/**
	 * 更新DashboardPortal
	 *
	 * @param dashboardPortalUpdate
	 * @param user
	 * @return
	 */
	@Override
	@Transactional
	public DashboardPortal updateDashboardPortal(DashboardPortalUpdate dashboardPortalUpdate, User user)
			throws NotFoundException, UnAuthorizedExecption, ServerException {

		DashboardPortal dashboardPortal = getDashboardPortal(dashboardPortalUpdate.getId());
		Long projectId = dashboardPortal.getProjectId();
		checkWritePermission(entity,  projectId, user, "update");

		Long id = dashboardPortal.getId();
		String name = dashboardPortalUpdate.getName();
		checkIsExist(name, id, projectId);
		
		if (isDisablePortal(id, projectId, user, getProjectPermission(projectId, user))) {
			alertUnAuthorized(entity, user, "delete");
		}
		
		BaseLock lock = getLock(entity, name, projectId);
		if (!lock.getLock()) {
			alertNameTaken(entity, name);
		}

		try {
			DashboardPortal originDashboardPortal = new DashboardPortal();
			BeanUtils.copyProperties(dashboardPortal, originDashboardPortal);
			BeanUtils.copyProperties(dashboardPortalUpdate, dashboardPortal);
			dashboardPortal.setUpdateBy(user.getId());
			dashboardPortal.setUpdateTime(new Date());

			updateDashboardPortal(dashboardPortal, dashboardPortalUpdate.getRoleIds(), user);
			optLogger.info(OptLogUtils.update(TableTypeEnum.DASHBOARD_PORTAL, originDashboardPortal, dashboardPortal));

			return dashboardPortal;
		}finally {
			releaseLock(lock);
		}
	}

	@Transactional
	protected void updateDashboardPortal(DashboardPortal dashboardPortal, List<Long> roleIds, User user) {
		if (dashboardPortalExtendMapper.update(dashboardPortal) != 1) {
			throw new ServerException("Update dashboardPortal fail");
		}

		relRolePortalExtendMapper.deleteByProtalId(dashboardPortal.getId());

		if (CollectionUtils.isEmpty(roleIds)) {
			return;
		}
		
		List<Role> roles = roleMapper.getRolesByIds(roleIds);
		List<RelRolePortal> list = roles.stream()
				.map(r -> {
					RelRolePortal rel = new RelRolePortal();
					rel.setRoleId(r.getId());
					rel.setPortalId(dashboardPortal.getId());
					rel.setCreateBy(user.getId());
					rel.setUpdateTime(new Date());
					rel.setVisible(false);
					return rel;
				})
				.collect(Collectors.toList());

		if (!CollectionUtils.isEmpty(list)) {
			relRolePortalExtendMapper.insertBatch(list);
			optLogger.info(OptLogUtils.insertBatch(TableTypeEnum.REL_ROLE_PORTAL, list));
		}
	}

    @Override
    public List<Long> getExcludeRoles(Long id) {
        return relRolePortalExtendMapper.getExcludeRoles(id);
    }

    @Override
    @Transactional
    public boolean postPortalVisibility(Role role, VizVisibility vizVisibility, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

    	DashboardPortal portal =getDashboardPortal(vizVisibility.getId());

        projectService.getProjectDetail(portal.getProjectId(), user, true);

        if (vizVisibility.isVisible()) {
            if (relRolePortalExtendMapper.delete(portal.getId(), role.getId()) > 0) {
	            optLogger.info(OptLogUtils.delete(TableTypeEnum.REL_ROLE_PORTAL, new RelRolePortal(portal.getId(), role.getId())));
            }
        } else {
	        RelRolePortal relRolePortal = new RelRolePortal(portal.getId(), role.getId());
	        relRolePortal.setCreateBy(user.getId());
	        relRolePortal.setUpdateTime(new Date());
			relRolePortal.setVisible(false);
            relRolePortalExtendMapper.insert(relRolePortal);
	        optLogger.info(OptLogUtils.insert(TableTypeEnum.REL_ROLE_PORTAL, relRolePortal));
        }

        return true;
    }

    /**
     * 删除DashboardPortal
     *
     * @param id
     * @param user
     * @return
     */
    @Override
    @Transactional
    public boolean deleteDashboardPortal(Long id, User user) throws NotFoundException, UnAuthorizedExecption {

    	DashboardPortal dashboardPortal = getDashboardPortal(id);
    	checkWritePermission(entity, dashboardPortal.getProjectId(), user, "delete");

		Long projectId = dashboardPortal.getProjectId();
		if (isDisablePortal(id, projectId, user, getProjectPermission(projectId, user))) {
			alertUnAuthorized(entity, user, "delete");
		}

        relRoleDashboardWidgetExtendMapper.deleteByPortalId(id);
        memDashboardWidgetExtendMapper.deleteByPortalId(id);
        relRoleDashboardExtendMapper.deleteByPortalId(id);
        dashboardExtendMapper.deleteByPortalId(id);

        if (dashboardPortalExtendMapper.deleteByPrimaryKey(id) == 1) {
            relRolePortalExtendMapper.deleteByProtalId(dashboardPortal.getId());
	        optLogger.info(OptLogUtils.delete(TableTypeEnum.DASHBOARD_PORTAL, dashboardPortal));
	        return true;
        }
        return false;
    }
}
