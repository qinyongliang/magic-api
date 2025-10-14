package org.ssssssss.magicapi.lsp.web.dto;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.model.PathMagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.utils.PathUtils;

public class DtoConverters {

    public static MagicGroupInfo fromGroup(Group g){
        if(g == null){
            return null;
        }
        MagicGroupInfo dto = new MagicGroupInfo();
        dto.setId(g.getId());
        dto.setName(g.getName());
        dto.setPath(g.getPath());
        dto.setParentId(g.getParentId());
        dto.setType(g.getType());
        dto.setCreateTime(g.getCreateTime());
        dto.setUpdateTime(g.getUpdateTime());
        return dto;
    }

    public static MagicFileInfo fromEntity(MagicEntity e, MagicResourceService service){
        if(e == null){
            return null;
        }
        MagicFileInfo dto = new MagicFileInfo();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setScript(e.getScript());
        dto.setGroupId(e.getGroupId());
        dto.setCreateTime(e.getCreateTime());
        dto.setUpdateTime(e.getUpdateTime());
        dto.setCreateBy(e.getCreateBy());
        dto.setUpdateBy(e.getUpdateBy());
        dto.setLocked(StringUtils.equals(Constants.LOCK, e.getLock()));

        // derive type from group
        Group group = service.getGroup(e.getGroupId());
        if(group != null){
            dto.setType(group.getType());
        }

        // path related fields for PathMagicEntity
        if(e instanceof PathMagicEntity){
            String groupPath = service.getGroupPath(e.getGroupId());
            dto.setGroupPath(groupPath);
            dto.setPath(((PathMagicEntity)e).getPath());
            // request mapping path (without method/prefix)
            String mappingPath = PathUtils.replaceSlash("/" + StringUtils.defaultString(groupPath) + "/" + StringUtils.defaultString(((PathMagicEntity) e).getPath()));
            dto.setRequestMapping(mappingPath);
        }

        if(e instanceof ApiInfo){
            ApiInfo api = (ApiInfo) e;
            dto.setMethod(api.getMethod());
            dto.setDescription(api.getDescription());
        }

        return dto;
    }
}