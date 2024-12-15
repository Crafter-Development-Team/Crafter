from ....common.class_loader.auto_load import preprocess_dictionary

dictionary = {
    "zh_CN": {
        "Crafter": "Crafter",
        "Plan": "方案",
        "Import World": "导入世界",
        "Materials Loader": "材质加载器",
        "ExampleAddon": "示例插件",
        "Resource Folder": "资源文件夹",
        "Int Config": "整数参数",
        "Boolean Config": "布尔参数",
        "Functions Preferences": "功能偏好",
        "ExampleOperator": "示例操作",
    }
}

dictionary = preprocess_dictionary(dictionary)

dictionary["zh_HANS"] = dictionary["zh_CN"]
