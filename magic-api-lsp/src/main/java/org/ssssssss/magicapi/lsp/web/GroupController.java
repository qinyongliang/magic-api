package org.ssssssss.magicapi.lsp.web;

import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.model.TreeNode;
import org.ssssssss.magicapi.lsp.web.dto.DtoConverters;
import org.ssssssss.magicapi.lsp.web.dto.MagicGroupInfo;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${magic-api.web:/}")
public class GroupController {

    private final MagicResourceService service = MagicConfiguration.getMagicResourceService();

    @GetMapping("/group/list")
    public JsonBean<List<MagicGroupInfo>> list(@RequestParam String type) {
        if (service == null) {
            return new JsonBean<>(Collections.emptyList());
        }
        TreeNode<Group> tree = service.tree(type);
        List<MagicGroupInfo> groups = tree == null ? Collections.emptyList() :
                tree.flat().stream()
                        .filter(it -> !Constants.ROOT_ID.equals(it.getId()))
                        .map(DtoConverters::fromGroup)
                        .collect(Collectors.toList());
        return new JsonBean<>(groups);
    }

    @GetMapping("/group/get/{groupId}")
    public JsonBean<MagicGroupInfo> get(@PathVariable String groupId) {
        if (service == null) {
            return new JsonBean<>((MagicGroupInfo) null);
        }
        Group g = service.getGroup(groupId);
        return new JsonBean<>(DtoConverters.fromGroup(g));
    }

    @PostMapping("/group/save")
    public JsonBean<Map<String,String>> save(@RequestBody MagicGroupInfo req) {
        if (service == null) {
            return new JsonBean<>((Map<String,String>) null);
        }
        Group g = new Group();
        g.setId(req.getId());
        g.setName(req.getName());
        g.setPath(req.getPath());
        g.setParentId(req.getParentId());
        g.setType(req.getType());
        g.setCreateTime(req.getCreateTime());
        g.setUpdateTime(req.getUpdateTime());
        boolean ok = service.saveGroup(g);
        Map<String,String> data = ok ? Collections.singletonMap("id", g.getId()) : null;
        return new JsonBean<>(data);
    }

    @DeleteMapping("/group/delete/{groupId}")
    public JsonBean<Boolean> delete(@PathVariable String groupId) {
        if (service == null) {
            return new JsonBean<>(false);
        }
        return new JsonBean<>(service.delete(groupId));
    }
}