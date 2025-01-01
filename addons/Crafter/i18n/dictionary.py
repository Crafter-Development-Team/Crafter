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
        "original":"原版",
        "Texture Interpolation":"纹理插值",
        "Set Texture Interpolation":"设置纹理插值",
            #==========加载材质==========
        "Load Materials":"加载材质",
        #==========操作解释==========
        "Import world":"导入世界",
        "Import the surface world":"导入表面世界",
        "Import the solid area":"导入实心区域",
        "Starting coordinates":"起始坐标",
        "Ending coordinates":"结束坐标",
        "Reload resources plans":"刷新纹理方案",
    }
}

dictionary = preprocess_dictionary(dictionary)

dictionary["zh_HANS"] = dictionary["zh_CN"]
