//
//  EXAlwaysAllowingManifestComparator.m
//  EXOta
//
//  Created by Michał Czernek on 14/10/2019.
//

#import "EXAlwaysAllowingManifestComparator.h"
#import "EXOtaUpdater.h"

@implementation EXAlwaysAllowingManifestComparator

-(BOOL) shouldDownloadBundle:(NSDictionary*)oldManifest forNew:(NSDictionary*)newManifest
{
    return YES;
}

@end
