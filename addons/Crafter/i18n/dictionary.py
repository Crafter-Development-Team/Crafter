from common.i18n.dictionary import preprocess_dictionary

dictionary = {
    "zh_CN": {
        "Plans":"方案",
        #==========面板==========
            #==========加载==========
                #==========加载背景==========
        "Background":"背景",
        "Load Background":"加载背景",
            #==========导入世界==========
        "Import World":"导入世界",
        "Import Editable Area":"导入可编辑区域",
        "Minecraft Saves":"Minecraft存档",
        "World path":"存档地址",
        "XYZ-1":"坐标1",
        "XYZ-2":"坐标2",
        "Point Cloud Mode":"点云模式",
        "History Worlds":"历史世界",
        "To use the history world settings":"使用历史世界设置",
        "Versions":"版本列表",
        "Resources List":"资源包列表",
        "Chunk Precision":"区块精度导出",
        "Keep Boundary":"保留边界",
        "Strict Surface Pruning":"严格剔除面",
        "Cull Cave":"剔除洞穴",
        "Export Light Block":"导出光源方块",
        "Only Export Light Block":"只导出光源方块",
        "Light Block Size":"光源方块大小",
        "Allow Double Face":"允许重叠面",
        "As Chunk":"分块",
        "Chunk Size":"分块大小",
        "Underwater LOD":"水下LOD",
        "LOD Auto Center":"LOD自动中心",
        "LOD Center X":"LOD中心X",
        "LOD Center Z":"LOD中心Z",
        "LOD0 Distance":"LOD0距离",
        "LOD1 Distance":"LOD1距离",
        "LOD2 Distance":"LOD2距离",
        "LOD3 Distance":"LOD3距离",
        "Game Resources":"游戏资源包",
            #==========替换资源包==========
        "Replace Resources":"替换资源包",
        "Resources":"资源包预设",
        "original":"原版",
        "Open Resources Plans":"打开资源包预设列表文件夹",
        "Reload Resources Plans":"刷新资源预设列表",
        "Resource":"资源包",
        "Up resource's priority":"提高资源包优先级",
        "Down resource's priority":"降低资源包优先级",
        "Texture Interpolation":"纹理插值",
        "Texture interpolation method":"纹理插值类型",
        "Set Texture Interpolation":"设置纹理插值",
            #==========加载材质==========
        "Load Materials":"加载材质",
        "Materials":"材质",
        "PBR Parser":"PBR解析器",
        "Mix Parser":"混合解析器",
        "Materials index":"材质索引",
        "Open Materials":"打开材质文件夹",
        "Reload Materials":"刷新材质列表",
        "Load Material":"加载材质",
        "Classification Basis":"材质分类依据",
        "default":"默认",
        "Open Classification Basis":"打开材质分类依据文件夹",
        "Reload Classification Basis":"刷新材质分类依据列表",
        #==========操作介绍==========
            #==========导入世界==========
        "Import world":"导入世界",
        "Import World":"导入世界",
        "History":"历史",
        "Import the surface world":"导入表面世界",
        "Import the solid area":"导入实心区域",
        "Starting coordinates":"起始坐标",
        "Ending coordinates":"结束坐标",
        "Enable this option when reporting a bug and include the shell output content":"反馈bug时,请启用此项并附带shell输出的内容",
            #==========替换资源包==========
        "Replace resources,but can only replace textures with the same name":"替换资源包,但只能替换同名纹理",
        "Set texture interpolation":"设置纹理插值",
            #==========加载材质==========
        "Parsed Normal Strength":"解析后法向强度",
        "How to parse PBR texture(and normal texture)":"如何解析PBR贴图(以及法线贴图)",
        "(1-R)**2,G as F0,Emission in Alpha":"(1-R)**2,G为F0,Alpha为自发光",
        "(1-R)**2,G as Metallic,Emission in Alpha":"(1-R)**2,G为金属度,Alpha为自发光",
        "1-R,G as Metallic,Emission in B":"1-R,G为金属度,B为自发光",
        "1-R,G as Metallic,No Emission":"1-R,G为金属度,无自发光",
        "Load material":"加载材质",
        "Classification basis":"材质分类依据",
        #==========节点接口==========
        "Normal Alpha":"法向Alpha",
        "Parsed Normal":"已解析法向",
        "Porosity":"孔隙率",
        #==========提示==========
        "No Selected Background!":"无选中背景!",
        "Can't find any versions!":"找不到任何版本!",
        "It's not a world path!":"这不是一个世界路径!",
        "Please set the save file into the Minecraft game folder!":"请将存档文件放入Minecraft游戏文件夹!",
        "WorldImporter didn't export obj!":"WorldImporter没有导出obj!",
        "Importing finished.Time used:":"导入完成。耗时:",
        "Haven't history worlds":"无历史世界记录",
        "WorldImporter.exe started in a new process":"WorldImporter.exe已在新进程启动",
        "At":"在",
        "WorldImporter.exe finished":"WorldImporter.exe结束运行",
        "imported":"导入完毕",
    }
}

dictionary = preprocess_dictionary(dictionary)

dictionary["zh_HANS"] = dictionary["zh_CN"]
