//  Copyright © 2018 650 Industries. All rights reserved.

#import <UMCore/UMExportedModule.h>
#import <UMCore/UMModuleRegistryConsumer.h>

//typedef NS_ENUM(NSInteger, EXNetworkType) {
//  EXNetworkTypeUnknown = 0,
//  EXNetworkTypeNone,
//  EXNetworkTypeCellular,
//  EXNetworkTypeWifi,
//};

static NSString *const EXNetworkTypeUnknown = @"UNKNOWN";
static NSString *const EXNetworkTypeNone = @"NONE";
static NSString *const EXNetworkTypeWifi = @"WIFI";
static NSString *const EXNetworkTypeCellular = @"CELLULAR";


@interface EXNetwork : UMExportedModule <UMModuleRegistryConsumer>
@end
