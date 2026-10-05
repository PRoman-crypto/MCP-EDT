/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.polischuk.edt.prl.tools.code.CodeSearchTool;
import com.polischuk.edt.prl.tools.code.FindReferencesTool;
import com.polischuk.edt.prl.tools.code.GetCallHierarchyTool;
import com.polischuk.edt.prl.tools.debug.LaunchDebuggerTool;
import com.polischuk.edt.prl.tools.docs.GetPlatformDocsTool;
import com.polischuk.edt.prl.tools.export.ExportObjectTool;
import com.polischuk.edt.prl.tools.code.GetModuleStructureTool;
import com.polischuk.edt.prl.tools.code.ListModulesTool;
import com.polischuk.edt.prl.tools.code.ReadMethodSourceTool;
import com.polischuk.edt.prl.tools.code.ReadModuleSourceTool;
import com.polischuk.edt.prl.tools.info.ListApplicationsTool;
import com.polischuk.edt.prl.tools.info.ListWorkspaceProjectsTool;
import com.polischuk.edt.prl.tools.jobs.GetJobStatusTool;
import com.polischuk.edt.prl.tools.jobs.ImportProjectTool;
import com.polischuk.edt.prl.tools.jobs.RebuildProjectTool;
import com.polischuk.edt.prl.tools.jobs.RemoveProjectTool;
import com.polischuk.edt.prl.tools.jobs.RunApplicationTool;
import com.polischuk.edt.prl.tools.jobs.RunVrunnerTool;
import com.polischuk.edt.prl.tools.jobs.UpdateInfobaseTool;
import com.polischuk.edt.prl.tools.jobs.YaxunitTestsTool;
import com.polischuk.edt.prl.tools.info.ShowEdtVersionTool;
import com.polischuk.edt.prl.tools.metadata.AiContextTool;
import com.polischuk.edt.prl.tools.metadata.GetConfigPropertiesTool;
import com.polischuk.edt.prl.tools.metadata.GetFormImageTool;
import com.polischuk.edt.prl.tools.metadata.GetObjectDetailsTool;
import com.polischuk.edt.prl.tools.metadata.GetObjectHelpTool;
import com.polischuk.edt.prl.tools.metadata.GetRoleRightsTool;
import com.polischuk.edt.prl.tools.metadata.GetMxlTool;
import com.polischuk.edt.prl.tools.metadata.GetSkdTool;
import com.polischuk.edt.prl.tools.metadata.GetSubsystemContentTool;
import com.polischuk.edt.prl.tools.metadata.GetTemplateTool;
import com.polischuk.edt.prl.tools.metadata.ListMetadataObjectsTool;
import com.polischuk.edt.prl.tools.validation.CodeReviewTool;
import com.polischuk.edt.prl.tools.validation.GetValidationErrorsTool;
import com.polischuk.edt.prl.tools.validation.ValidateQueryTool;
import com.polischuk.edt.prl.tools.write.DiffModuleTool;
import com.polischuk.edt.prl.tools.write.EditMetadataTool;
import com.polischuk.edt.prl.tools.write.WriteModuleSourceTool;

/** Реєстр інструментів; порядок реєстрації зберігається у tools/list. */
public final class ToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public static ToolRegistry createDefault() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ShowEdtVersionTool());
        registry.register(new ListWorkspaceProjectsTool());
        registry.register(new ListApplicationsTool());
        registry.register(new GetConfigPropertiesTool());
        registry.register(new ListMetadataObjectsTool());
        registry.register(new GetObjectDetailsTool());
        registry.register(new GetObjectHelpTool());
        registry.register(new GetSubsystemContentTool());
        registry.register(new GetRoleRightsTool());
        registry.register(new GetTemplateTool());
        registry.register(new GetSkdTool());
        registry.register(new GetMxlTool());
        registry.register(new AiContextTool());
        registry.register(new GetFormImageTool());
        registry.register(new GetValidationErrorsTool());
        registry.register(new ListModulesTool());
        registry.register(new GetModuleStructureTool());
        registry.register(new ReadModuleSourceTool());
        registry.register(new ReadMethodSourceTool());
        registry.register(new CodeSearchTool());
        registry.register(new FindReferencesTool());
        registry.register(new GetCallHierarchyTool());
        registry.register(new WriteModuleSourceTool());
        registry.register(new DiffModuleTool());
        registry.register(new EditMetadataTool());
        registry.register(new ValidateQueryTool());
        registry.register(new CodeReviewTool());
        registry.register(new GetPlatformDocsTool());
        registry.register(new ExportObjectTool());
        registry.register(new RunVrunnerTool());
        registry.register(new GetJobStatusTool());
        registry.register(new RunApplicationTool());
        registry.register(new UpdateInfobaseTool());
        registry.register(new RebuildProjectTool());
        registry.register(new ImportProjectTool());
        registry.register(new RemoveProjectTool());
        registry.register(new YaxunitTestsTool());
        registry.register(new LaunchDebuggerTool());
        return registry;
    }

    public void register(McpTool tool) {
        tools.put(tool.name(), tool);
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    public Collection<McpTool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }
}
