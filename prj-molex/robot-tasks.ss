{
    "palletTransfer0112": {
        "id": null,
        "name": "palletTransfer0112",
        "description": "栈板列运输",
        "httpApiList": [
          {
            "path": "/task-by-operator/palletTransfer0112",
            "method": "POST",
            "reqDemoBody": "{\"params\":{\"from\": \"ST01\", \"to\": \"MD2\"}}",
            "successResponseCode": 201,
            "responseDecorator": "",
            "components": [
              {
                "def": "TrimRequired",
                "params": {
                  "value": "=httpBody.params.from",
                  "message": "起点不能为空"
                },
                "returnName": "from",
                "components": []
              },
              {
                "def": "SetTaskExtraVariables",
                "params": { "name": "fromType", "value": "=from" },
                "returnName": null,
                "components": []
              },
              {
                "def": "TrimRequired",
                "params": {
                  "value": "=httpBody.params.to",
                  "message": "终点不能为空"
                },
                "returnName": "to",
                "components": []
              },
              {
                "def": "SetTaskExtraVariables",
                "params": { "name": "toType", "value": "=to" },
                "returnName": null,
                "components": []
              },
              {
                "def": "TrimRequired",
                "params": {
                  "value": "=httpBody.params.num",
                  "message": "数量不能为空"
                },
                "returnName": "num",
                "components": []
              },
              {
                "def": "checkTransferSite",
                "params": { "location": "=from" },
                "returnName": null,
                "components": []
              },
              {
                "def": "SetTaskExtraVariables",
                "params": { "name": "fromSite", "value": "=from + \"-01\"" },
                "returnName": null,
                "components": []
              },
              {
                "def": "moLex:getOneSite",
                "params": { "type": "=toType" },
                "returnName": "toSite",
                "components": []
              },
              {
                "def": "SetTaskExtraVariables",
                "params": { "name": "toSite", "value": "=toSite" },
                "returnName": null,
                "components": []
              },
              {
                "def": "SetTaskExtraVariables",
                "params": { "name": "num", "value": "=num" },
                "returnName": null,
                "components": []
              },
              {
                "def": "SetTaskExtraVariables",
                "params": { "name": "auto", "value": "false" },
                "returnName": null,
                "components": []
              },
              {
                "def": "SetTaskExtraVariables",
                "params": { "name": "areaCheck", "value": "false" },
                "returnName": null,
                "components": []
              },
              {
                "def": "SetLocationName",
                "params": { "destination": "=pre2", "value": "=\"Pre-\" + toType" },
                "returnName": null,
                "components": []
              },
              {
                "def": "SetLocationName",
                "params": { "destination": "=f", "value": "=fromSite" },
                "returnName": null,
                "components": []
              },
              {
                "def": "If",
                "params": {
                  "value": "=fromType == \"ST01\"||fromType == \"ST02\"||fromType == \"ST03\""
                },
                "returnName": null,
                "components": [
                  {
                    "def": "CodeBlock",
                    "params": {},
                    "returnName": null,
                    "components": [
                      {
                        "def": "SetLocationName",
                        "params": { "destination": "=f2", "value": "Pre-Stamping" },
                        "returnName": null,
                        "components": []
                      },
                      {
                        "def": "ReplaceDestinationProperty",
                        "params": {
                          "destination": "=f2",
                          "key": "end_height",
                          "value": "0.3"
                        },
                        "returnName": null,
                        "components": []
                      }
                    ]
                  },
                  {
                    "def": "SetLocationName",
                    "params": {
                      "destination": "=f2",
                      "value": "=\"Pre-\" + fromType"
                    },
                    "returnName": null,
                    "components": []
                  }
                ]
              },
              {
                "def": "kuerle:setIndex",
                "params": {},
                "returnName": null,
                "components": []
              }
            ]
          }
        ],
        "transports": [
          {
            "refName": "",
            "description": "栈板列运输:起点",
            "category": "",
            "seqGroup": "pallet",
            "stages": [
              {
                "refName": "",
                "description": "前置检查",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "SetLocationName",
                    "params": {
                      "destination": "=task.transports[0].stages[2]",
                      "value": "=fromType + \"-Wait\""
                    },
                    "returnName": null,
                    "components": []
                  },
                  {
                    "def": "If",
                    "params": { "value": "=auto == true" },
                    "returnName": null,
                    "components": [
                      {
                        "def": "CodeBlock",
                        "params": {},
                        "returnName": null,
                        "components": [
                          {
                            "def": "moLex:getOneSitePallet",
                            "params": { "type": "=toType" },
                            "returnName": "toSite",
                            "components": []
                          },
                          {
                            "def": "SetTaskExtraVariables",
                            "params": { "name": "toSite", "value": "=toSite" },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "SetLocationName",
                            "params": {
                              "destination": "=task.transports[1].stages[0]",
                              "value": "=fromSite"
                            },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "SetLocationName",
                            "params": {
                              "destination": "=task.transports[2].stages[2]",
                              "value": "=\"Pre-\" + toType"
                            },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "If",
                            "params": {
                              "value": "=fromType == \"ST01\"||fromType == \"ST02\"||fromType == \"ST03\""
                            },
                            "returnName": null,
                            "components": [
                              {
                                "def": "CodeBlock",
                                "params": {},
                                "returnName": null,
                                "components": [
                                  {
                                    "def": "SetLocationName",
                                    "params": {
                                      "destination": "=task.transports[1].stages[1]",
                                      "value": "Pre-Stamping"
                                    },
                                    "returnName": null,
                                    "components": []
                                  },
                                  {
                                    "def": "ReplaceDestinationProperty",
                                    "params": {
                                      "destination": "=task.transports[1].stages[1]",
                                      "key": "end_height",
                                      "value": "0.3"
                                    },
                                    "returnName": null,
                                    "components": []
                                  }
                                ]
                              },
                              {
                                "def": "SetLocationName",
                                "params": {
                                  "destination": "=task.transports[1].stages[1]",
                                  "value": "=fromType+\"-01\""
                                },
                                "returnName": null,
                                "components": []
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "refName": "",
                "description": "下发检查",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "kuerle:checkSameType",
                    "params": {},
                    "returnName": null,
                    "components": []
                  },
                  {
                    "def": "kuerle:addToSentList",
                    "params": {},
                    "returnName": null,
                    "components": []
                  },
                  {
                    "def": "If",
                    "params": { "value": "=auto != true" },
                    "returnName": null,
                    "components": [
                      {
                        "def": "sendAllTasks",
                        "params": {},
                        "returnName": null,
                        "components": []
                      }
                    ]
                  }
                ]
              },
              {
                "refName": "",
                "description": "等待点",
                "forRoute": true,
                "operation": "Wait",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": []
              },
              {
                "refName": "",
                "description": "锁定起点",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "pallet:chose",
                    "params": { "type": "=fromType" },
                    "returnName": "fromid",
                    "components": []
                  },
                  {
                    "def": "If",
                    "params": { "value": "=fromid == \"\"" },
                    "returnName": null,
                    "components": [
                      {
                        "def": "CodeBlock",
                        "params": {},
                        "returnName": null,
                        "components": [
                          {
                            "def": "SkipTransport",
                            "params": {
                              "transportIndex": "1",
                              "reason": "无可用位置"
                            },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "SkipTransport",
                            "params": {
                              "transportIndex": "2",
                              "reason": "无可用位置"
                            },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "SkipTransport",
                            "params": {
                              "transportIndex": "3",
                              "reason": "无可用位置"
                            },
                            "returnName": null,
                            "components": []
                          }
                        ]
                      },
                      {
                        "def": "CodeBlock",
                        "params": {},
                        "returnName": null,
                        "components": [
                          {
                            "def": "LockSiteOnlyIfNotLock",
                            "params": { "siteId": "=fromid" },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "SetLocationName",
                            "params": { "destination": "=f", "value": "=fromid" },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "SetTaskExtraVariables",
                            "params": { "name": "fromId", "value": "=fromid" },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "If",
                            "params": {
                              "value": "=fromType == \"ST01\"||fromType == \"ST02\"||fromType == \"ST03\""
                            },
                            "returnName": null,
                            "components": [
                              {
                                "def": "CodeBlock",
                                "params": {},
                                "returnName": null,
                                "components": [
                                  {
                                    "def": "SetLocationName",
                                    "params": {
                                      "destination": "=f2",
                                      "value": "Pre-Stamping"
                                    },
                                    "returnName": null,
                                    "components": []
                                  },
                                  {
                                    "def": "ReplaceDestinationProperty",
                                    "params": {
                                      "destination": "=f2",
                                      "key": "end_height",
                                      "value": "0.3"
                                    },
                                    "returnName": null,
                                    "components": []
                                  }
                                ]
                              },
                              {
                                "def": "CodeBlock",
                                "params": {},
                                "returnName": null,
                                "components": [
                                  {
                                    "def": "If",
                                    "params": {
                                      "value": "=fromId.contains(\"-01\")"
                                    },
                                    "returnName": null,
                                    "components": [
                                      {
                                        "def": "SetLocationName",
                                        "params": {
                                          "destination": "=f2",
                                          "value": "=\"Pre-\" + fromType"
                                        },
                                        "returnName": null,
                                        "components": []
                                      },
                                      {
                                        "def": "SetLocationName",
                                        "params": {
                                          "destination": "=f2",
                                          "value": "=fromType+\"-01\""
                                        },
                                        "returnName": null,
                                        "components": []
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          },
          {
            "refName": "",
            "description": "取货",
            "category": "",
            "seqGroup": "pallet",
            "stages": [
              {
                "refName": "f",
                "description": "起点",
                "forRoute": true,
                "operation": "ForkLoad",
                "location": "",
                "properties": "[{\"key\":\"end_height\",\"value\":\"0.15\"}]",
                "maxRetries": null,
                "retryDelay": null,
                "components": []
              },
              {
                "refName": "f2",
                "description": "抬升点",
                "forRoute": true,
                "operation": "ForkHeight",
                "location": "",
                "properties": "[{\"key\":\"fork_mid_height\",\"value\":\"0.3\"}]",
                "maxRetries": null,
                "retryDelay": null,
                "components": []
              }
            ]
          },
          {
            "refName": "",
            "description": "终点前置点",
            "category": "",
            "seqGroup": "pallet",
            "stages": [
              {
                "refName": "",
                "description": "置空",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "MarkSiteIdle",
                    "params": { "siteId": "=fromId" },
                    "returnName": null,
                    "components": []
                  }
                ]
              },
              {
                "refName": "",
                "description": "释放起点",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "UnlockSiteIfLocked",
                    "params": { "siteId": "=fromId" },
                    "returnName": null,
                    "components": []
                  }
                ]
              },
              {
                "refName": "pre2",
                "description": "终点前置点",
                "forRoute": true,
                "operation": "Wait",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": []
              }
            ]
          },
          {
            "refName": "",
            "description": "栈板列运输:终点",
            "category": "",
            "seqGroup": "pallet",
            "stages": [
              {
                "refName": "",
                "description": "通知",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "passArea",
                    "params": {},
                    "returnName": "toSiteId",
                    "components": []
                  },
                  {
                    "def": "If",
                    "params": { "value": "=toSiteId == \"\"" },
                    "returnName": null,
                    "components": [
                      {
                        "def": "CodeBlock",
                        "params": {},
                        "returnName": null,
                        "components": [
                          {
                            "def": "AlertOperator",
                            "params": {
                              "message": "=\"区域[\"+toType+\"]无可用位置\"",
                              "workStations": "",
                              "workTypes": "",
                              "all": "true"
                            },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "twinkleTypeRed",
                            "params": { "type": "=toType" },
                            "returnName": null,
                            "components": []
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "refName": "",
                "description": "检查库位",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "passArea",
                    "params": {},
                    "returnName": "=toSiteId",
                    "components": []
                  },
                  {
                    "def": "If",
                    "params": { "value": "=toSiteId == \"\"" },
                    "returnName": null,
                    "components": [
                      {
                        "def": "ThrowToWait",
                        "params": { "reason": "=\"区域[\"+toType+\"]无可用位置\"" },
                        "returnName": null,
                        "components": []
                      }
                    ]
                  },
                  {
                    "def": "pass",
                    "params": { "location": "=toSiteId", "type": "to" },
                    "returnName": null,
                    "components": []
                  },
                  {
                    "def": "If",
                    "params": { "value": "=pass == \"false\"" },
                    "returnName": null,
                    "components": [
                      {
                        "def": "CodeBlock",
                        "params": {},
                        "returnName": null,
                        "components": [
                          {
                            "def": "ThrowToWait",
                            "params": { "reason": "=\"库位\"+toSiteId+\"需更新\"" },
                            "returnName": null,
                            "components": []
                          }
                        ]
                      },
                      {
                        "def": "CodeBlock",
                        "params": {},
                        "returnName": null,
                        "components": [
                          {
                            "def": "markComplete",
                            "params": {},
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "cancelTwinkleTypeRed",
                            "params": { "type": "=toType" },
                            "returnName": null,
                            "components": []
                          },
                          {
                            "def": "lightYellow",
                            "params": { "location": "=toSiteId" },
                            "returnName": null,
                            "components": []
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "refName": "",
                "description": "锁定终点",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "SetLocationName",
                    "params": {
                      "destination": "=task.transports[3].stages[3]",
                      "value": "=toSiteId"
                    },
                    "returnName": null,
                    "components": []
                  },
                  {
                    "def": "LockSiteOnlyIfNotLock",
                    "params": { "siteId": "=toSiteId" },
                    "returnName": null,
                    "components": []
                  }
                ]
              },
              {
                "refName": "t",
                "description": "终点",
                "forRoute": true,
                "operation": "ForkUnload",
                "location": "",
                "properties": "[{\"key\":\"end_height\",\"value\":\"0.09\"}]",
                "maxRetries": null,
                "retryDelay": null,
                "components": []
              },
              {
                "refName": "",
                "description": "黄灯闪烁",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "MarkSiteNotIdle",
                    "params": { "siteId": "=toSiteId" },
                    "returnName": null,
                    "components": []
                  },
                  {
                    "def": "lightYellowTwinkle",
                    "params": { "location": "=toSiteId" },
                    "returnName": null,
                    "components": []
                  }
                ]
              },
              {
                "refName": "",
                "description": "释放终点",
                "forRoute": false,
                "operation": "",
                "location": "",
                "properties": "[]",
                "maxRetries": null,
                "retryDelay": null,
                "components": [
                  {
                    "def": "UnlockSiteIfLocked",
                    "params": { "siteId": "=toSiteId" },
                    "returnName": null,
                    "components": []
                  }
                ]
              }
            ]
          }
        ],
        "static": false,
        "parallel": false,
        "noTransport": false
      }
}