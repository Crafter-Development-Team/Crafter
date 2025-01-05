from common.i18n.dictionary import preprocess_dictionary

dictionary = {
    "zh_CN": {
        "Crafter": "Crafter",
        "Plans":"方案",
        #==========面板==========
            #==========导入世界==========
        "Import World":"导入世界",
        "Import Editable Area":"导入可编辑区域",
        "World path":"存档地址",
        "XYZ-1":"坐标1",
        "XYZ-2":"坐标2",
            #==========导入纹理==========
        "Import Resources":"导入纹理",
        "Resources Plans index":"纹理包预设索引",
        "original":"原版",
        "Open Resources Plans":"打开纹理包预设列表文件夹",
        "Reload Resources Plans":"刷新纹理预设列表",
        "Texture Interpolation":"纹理插值",
        "Texture interpolation method.":"纹理插值类型",
        "Set Texture Interpolation":"设置纹理插值",
            #==========加载材质==========
        "Load Materials":"加载材质",
        "Open Materials":"打开材质文件夹",
        "Reload Materials":"刷新材质列表",
        
        #==========操作解释==========
            #==========导入世界==========
        "Import world":"导入世界",
        "Import the surface world":"导入表面世界",
        "Import the solid area":"导入实心区域",
        "Starting coordinates":"起始坐标",
        "Ending coordinates":"结束坐标",
            #==========导入纹理==========
        "Reload resources plans":"刷新纹理预设列表",
        "Open resources plans":"打开纹理包预设列表文件夹",
            #==========加载材质==========
        "Open materials":"打开材质文件夹",
        "Reload materials":"刷新材质列表",
    }
}

dictionary = preprocess_dictionary(dictionary)

dictionary["zh_HANS"] = dictionary["zh_CN"]
