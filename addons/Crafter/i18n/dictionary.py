from common.i18n.dictionary import preprocess_dictionary

dictionary = {
    "zh_CN": {
        "Crafter": "Crafter",
        #==========面板==========
            #==========导入世界==========
        "Import World": "导入世界",
        "World path": "存档地址",
        "XYZ-1":"坐标1",
        "XYZ-2":"坐标2",
            #==========导入纹理==========
        "Import Resource": "导入纹理"
            #==========加载材质==========
        "Load Materials": "加载材质",
        "Plans": "方案",
        #==========操作解释==========
        "Import world": "导入世界",
    }
}

dictionary = preprocess_dictionary(dictionary)

dictionary["zh_HANS"] = dictionary["zh_CN"]
