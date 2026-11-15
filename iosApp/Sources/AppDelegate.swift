import UIKit

/*
 * iOS 原生壳（AppDelegate 这一半）：只管进程级的事 + 把场景配置交出去。
 * 窗口和 rootViewController 全在 SceneDelegate 里建 —— Apple 从 iOS 26/27 起在收口
 * "AppDelegate 单窗口"那条老路（TN3187），scene 生命周期才是长久活路。
 */
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        true
    }

    // 两条路都写好：plist 里 UISceneDelegateClassName 钉的是 @objc(SceneDelegate) 那个名字，
    // 这里再把 delegateClass 给死一次。任一条通就够，不赌 XcodeGen 现生成的 plist 里那串变量怎么展开。
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(
            name: "Default Configuration",
            sessionRole: connectingSceneSession.role
        )
        config.delegateClass = SceneDelegate.self
        return config
    }
}
