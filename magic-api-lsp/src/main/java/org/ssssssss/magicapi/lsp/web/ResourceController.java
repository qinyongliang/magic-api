package org.ssssssss.magicapi.lsp.web;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.function.model.FunctionInfo;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.lsp.web.dto.DtoConverters;
import org.ssssssss.magicapi.lsp.web.dto.MagicFileInfo;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${magic-api.web:/}")
public class ResourceController {

    private final MagicResourceService service = MagicConfiguration.getMagicResourceService();

    @GetMapping("/{type}/list")
    public JsonBean<List<MagicFileInfo>> list(@PathVariable String type,
                                              @RequestParam(required=false) String groupId) {
        if (service == null) {
            return new JsonBean<>(Collections.emptyList());
        }
        List<MagicEntity> entities;
        if (StringUtils.isBlank(groupId)) {
            entities = service.files(type);
        } else {
            entities = service.listFiles(groupId);
        }
        List<MagicFileInfo> result = entities.stream()
                .map(e -> DtoConverters.fromEntity(e, service))
                .collect(Collectors.toList());
        return new JsonBean<>(result);
    }

    @PostMapping("/{type}/save")
    public JsonBean<Map<String,String>> save(@PathVariable String type, @RequestBody MagicFileInfo req) {
        if (service == null) {
            return new JsonBean<>((Map<String,String>) null);
        }
        boolean ok = false;
        if ("api".equals(type)) {
            ApiInfo api = new ApiInfo();
            api.setId(req.getId());
            api.setName(req.getName());
            api.setGroupId(req.getGroupId());
            api.setScript(req.getScript());
            api.setPath(req.getPath());
            api.setMethod(req.getMethod());
            api.setDescription(req.getDescription());
            ok = service.saveFile(api);
            req.setId(api.getId());
        } else if ("function".equals(type)) {
            FunctionInfo func = new FunctionInfo();
            func.setId(req.getId());
            func.setName(req.getName());
            func.setGroupId(req.getGroupId());
            func.setScript(req.getScript());
            func.setPath(req.getPath());
            ok = service.saveFile(func);
            req.setId(func.getId());
        } else {
            return new JsonBean<>((Map<String,String>) null);
        }
        Map<String,String> data = ok ? Collections.singletonMap("id", req.getId()) : null;
        return new JsonBean<>(data);
    }
}