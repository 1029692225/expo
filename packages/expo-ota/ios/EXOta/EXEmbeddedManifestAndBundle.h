//
//  EXEmbeddedManifestAndBundle.h
//  EXOta
//
//  Created by Michał Czernek on 25/10/2019.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface EXEmbeddedManifestAndBundle : NSObject

-(NSDictionary*) readManifest;
-(NSString*) readBundlePath;

@end

NS_ASSUME_NONNULL_END
