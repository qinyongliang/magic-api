package org.ssssssss.magicapi.lsp.web;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.function.model.FunctionInfo;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.TreeNode;
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

    private static final Logger logger = LoggerFactory.getLogger(ResourceController.class);

    private final MagicResourceService service;

    public ResourceController(MagicResourceService service) {
        this.service = service;
        if (this.service == null) {
            logger.warn("MagicResourceService bean is null in ResourceController; check magic-api starter and resource configuration");
        } else {
            logger.info("MagicResourceService injected into ResourceController");
        }
    }

    /**
     * 统一资源目录：返回 /magic-api/ 之后的所有目录路径（例如：api、api/user、function/util 等）。
     */
    @GetMapping("/resource/dirs")
    public JsonBean<List<String>> resourceDirs() {
        if (service == null) {
            logger.error("MagicResourceService is null when handling resource dirs");
            return new JsonBean<>(Collections.emptyList());
        }
        try {
            org.ssssssss.magicapi.core.resource.Resource root = service.getResource();
            List<String> result = root.dirs().stream().map(Resource::getFilePath).collect(Collectors.toList());
            return new JsonBean<>(result);
        } catch (Throwable t) {
            logger.error("Failed to fetch resource dirs", t);
            return new JsonBean<>(Collections.emptyList());
        }
    }

    /**
     * 统一资源文件：按目录(相对路径)返回该目录下的文件列表。
     * 入参示例：dir=api/user 或 dir=function 等。
     */
    @GetMapping("/resource/files")
    public JsonBean<List<MagicFileInfo>> resourceFiles(@RequestParam("dir") String dir) {
        if (service == null) {
            logger.error("MagicResourceService is null when handling resource files, dir: {}", dir);
            return new JsonBean<>(Collections.emptyList());
        }
        if (StringUtils.isBlank(dir)) {
            return new JsonBean<>(Collections.emptyList());
        }
        try {
            String[] parts = dir.split("/", 2);
            String type = parts[0];
            String groupPathSub = parts.length > 1 ? parts[1] : "";
            String groupId = resolveGroupId(type, groupPathSub);
            if (groupId == null || groupId.equals("0")) {
                return new JsonBean<>(Collections.emptyList());
            }
            List<MagicFileInfo> result = service.listFiles(groupId).stream()
                    .map(e -> DtoConverters.fromEntity(e, service))
                    .collect(Collectors.toList());
            return new JsonBean<>(result);
        } catch (Throwable t) {
            logger.error("Failed to fetch resource files for dir: {}", dir, t);
            return new JsonBean<>(Collections.emptyList());
        }
    }

    /**
     * 统一资源：根据文件ID获取文件详情。
     */
    @GetMapping("/resource/get/{id}")
    public JsonBean<MagicFileInfo> resourceGet(@PathVariable("id") String id) {
        if (service == null) {
            logger.error("MagicResourceService is null when handling resource get, id: {}", id);
            return new JsonBean<>((MagicFileInfo) null);
        }
        MagicEntity entity = service.file(id);
        if (entity == null) {
            return new JsonBean<>((MagicFileInfo) null);
        }
        return new JsonBean<>(DtoConverters.fromEntity(entity, service));
    }

    /**
     * 统一资源保存：根据类型与目录路径保存文件（创建或更新）。
     * 请求体兼容 MagicFileInfo，优先使用 groupPath 中的目录信息。
     */
    @PostMapping("/resource/save")
    public JsonBean<Map<String, String>> resourceSave(@RequestBody MagicFileInfo req) {
        if (service == null) {
            logger.error("MagicResourceService is null when handling resource save: {}", req);
            return new JsonBean<>((Map<String, String>) null);
        }
        String type = Objects.toString(req.getType(), null);
        String groupPath = Objects.toString(req.getGroupPath(), null);
        String groupPathSub = null;
        if (StringUtils.isBlank(type) && StringUtils.isNotBlank(groupPath)) {
            String[] parts = groupPath.split("/", 2);
            type = parts[0];
            groupPathSub = parts.length > 1 ? parts[1] : "";
        } else if (StringUtils.isNotBlank(type)) {
            if (StringUtils.isBlank(groupPath)) {
                groupPathSub = "";
            } else {
                String[] parts = groupPath.split("/", 2);
                groupPathSub = parts.length > 1 ? parts[1] : "";
            }
        }

        if (StringUtils.isBlank(type)) {
            logger.error("Resource save missing type: {}", req);
            return new JsonBean<>((Map<String, String>) null);
        }

        String targetGroupId = resolveGroupId(type, Objects.toString(groupPathSub, ""));
        if (targetGroupId == null) {
            // 回退到类型根目录
            targetGroupId = resolveGroupId(type, "");
        }

        boolean ok = false;
        String id = null;
        try {
            if ("api".equals(type)) {
                ApiInfo api = new ApiInfo();
                api.setId(req.getId());
                api.setName(req.getName());
                api.setGroupId(targetGroupId);
                api.setScript(req.getScript());
                // 兼容 requestMapping/path 字段
                if (StringUtils.isNotBlank(req.getRequestMapping())) {
                    api.setPath(req.getRequestMapping());
                } else if (StringUtils.isNotBlank(req.getPath())) {
                    api.setPath(req.getPath());
                }
                if (StringUtils.isNotBlank(req.getMethod())) {
                    api.setMethod(req.getMethod());
                }
                api.setDescription(req.getDescription());
                ok = service.saveFile(api);
                id = api.getId();
            } else if ("function".equals(type)) {
                FunctionInfo func = new FunctionInfo();
                func.setId(req.getId());
                func.setName(req.getName());
                func.setGroupId(targetGroupId);
                func.setScript(req.getScript());
                if (StringUtils.isNotBlank(req.getPath())) {
                    func.setPath(req.getPath());
                }
                func.setDescription(req.getDescription());
                ok = service.saveFile(func);
                id = func.getId();
            } else {
                logger.error("Unsupported resource type for save: {}", type);
                return new JsonBean<>((Map<String, String>) null);
            }
        } catch (Throwable t) {
            logger.error("Failed to save resource: {}", req, t);
            return new JsonBean<>((Map<String, String>) null);
        }

        Map<String, String> data = ok ? Collections.singletonMap("id", id) : null;
        return new JsonBean<>(data);
    }

    @GetMapping("/{type}/list")
    public JsonBean<List<MagicFileInfo>> list(@PathVariable String type,
                                              @RequestParam(required=false) String groupId) {
        if (service == null) {
            logger.error("MagicResourceService is null when handling file list for type: {}, groupId: {}", type, groupId);
            return new JsonBean<>(Collections.emptyList());
        }
        // 兼容旧接口：当未传 groupId 时，返回类型下所有文件；否则返回指定分组文件。
        List<MagicEntity> entities = new ArrayList<>();
        try {
            if (StringUtils.isBlank(groupId)) {
                TreeNode<Group> root = service.tree().get(type);
                if (root != null) {
                    List<String> groupIds = root
                            .flat()
                            .stream()
                            .map(g -> g.getId())
                            .collect(Collectors.toList());
                    for (String gid : groupIds) {
                        entities.addAll(service.listFiles(gid));
                    }
                }
            } else {
                entities = service.listFiles(groupId);
            }
        } catch (Throwable t) {
            logger.error("Failed to list files for type: {} groupId: {}", type, groupId, t);
        }
        List<MagicFileInfo> result = entities.stream()
                .map(e -> DtoConverters.fromEntity(e, service))
                .collect(Collectors.toList());
        return new JsonBean<>(result);
    }

    @PostMapping("/{type}/save")
    public JsonBean<Map<String,String>> save(@PathVariable String type, @RequestBody MagicFileInfo req) {
        if (service == null) {
            logger.error("MagicResourceService is null when handling file save for type: {}, req: {}", type, req);
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

    /**
     * 根据类型与分组路径(不含类型段)解析分组ID（形如 type:uuid）。
     */
    private String resolveGroupId(String type, String groupPathSub) {
        try {
            Map<String, TreeNode<Group>> tree = service.tree();
            TreeNode<Group> root = tree.get(type);
            if (root == null) return null;
            if (StringUtils.isBlank(groupPathSub)) {
                return root.getNode().getId();
            }
            String[] segs = groupPathSub.split("/");
            TreeNode<Group> current = root;
            for (String seg : segs) {
                if (StringUtils.isBlank(seg)) continue;
                Optional<TreeNode<Group>> next = current.getChildren().stream()
                        .filter(n -> Objects.equals(n.getNode().getName(), seg))
                        .findFirst();
                if (!next.isPresent()) {
                    return null;
                }
                current = next.get();
            }
            return current.getNode().getId();
        } catch (Throwable t) {
            logger.error("Failed to resolve groupId by path. type: {}, path: {}", type, groupPathSub, t);
            return null;
        }
    }
}